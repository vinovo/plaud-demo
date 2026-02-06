package demo.nexa.clinical_transcription_demo.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import demo.nexa.clinical_transcription_demo.asr.NexaAsrEngine
import demo.nexa.clinical_transcription_demo.audio.WaveformExtractor
import demo.nexa.clinical_transcription_demo.common.BackgroundProgressManager
import demo.nexa.clinical_transcription_demo.data.audio.AudioFileManager
import demo.nexa.clinical_transcription_demo.data.local.AppDatabase
import demo.nexa.clinical_transcription_demo.data.mapper.toDomain
import demo.nexa.clinical_transcription_demo.data.mapper.toEntity
import demo.nexa.clinical_transcription_demo.domain.model.NoteSource
import demo.nexa.clinical_transcription_demo.domain.model.NoteStatus
import demo.nexa.clinical_transcription_demo.domain.model.RecordingNote
import demo.nexa.clinical_transcription_demo.llm.NexaLlmEngine
import demo.nexa.clinical_transcription_demo.llm.SoapGenerationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Repository for managing recording notes.
 * Thin layer over DAO + file operations.
 * 
 * Maintains its own coroutine scope for background work (transcription, summary generation)
 * that survives ViewModel lifecycle.
 */
class NotesRepository(
    private val database: AppDatabase,
    private val audioFileManager: AudioFileManager,
    private val context: Context
) {
    
    private val dao = database.recordingNoteDao()
    private val waveformExtractor = WaveformExtractor.getInstance()
    private val progressManager = BackgroundProgressManager.getInstance()
    
    // Background scope for long-running tasks (transcription, summary generation)
    // Uses SupervisorJob so individual task failures don't cancel other tasks
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * Observe all notes as a Flow.
     */
    fun observeAllNotes(): Flow<List<RecordingNote>> {
        return dao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }
    
    /**
     * Observe a single note by ID.
     */
    fun observeNoteById(id: String): Flow<RecordingNote?> {
        return dao.observeById(id).map { it?.toDomain() }
    }
    
    /**
     * Get a single note by ID (one-shot, not reactive).
     */
    suspend fun getNoteById(id: String): RecordingNote? {
        return dao.getById(id)?.toDomain()
    }
    
    /**
     * Create a new note for a recording.
     * Automatically extracts waveform data from the audio file.
     * 
     * @param id The note ID (must match the audio file base name)
     * @param title Note title
     * @param audioFile The audio file that was recorded (should be WAV)
     * @param durationMs Duration in milliseconds (optional)
     * @return The created note
     */
    suspend fun createRecordedNote(
        id: String,
        title: String,
        audioFile: File,
        durationMs: Long? = null
    ): RecordingNote {
        val now = System.currentTimeMillis()
        
        // Extract waveform data from audio file (for playback visualization)
        val waveformData = waveformExtractor.extractWaveform(audioFile, targetSampleCount = 200)
            .getOrNull()
        
        val note = RecordingNote(
            id = id,
            createdAtEpochMs = now,
            title = title,
            audioFileName = audioFile.name,
            durationMs = durationMs,
            source = NoteSource.RECORDED,
            status = NoteStatus.NEW,
            transcriptText = null,
            summaryText = null,
            errorMessage = null,
            waveformData = waveformData
        )
        
        dao.insert(note.toEntity())
        return note
    }
    
    /**
     * Create a new note for an imported audio file.
     * This will copy the audio file into app storage and extract waveform data.
     * 
     * @param title Note title
     * @param sourceUri Content URI of the imported audio
     * @param extension File extension (e.g., "m4a", "mp3", "wav")
     * @param durationMs Duration in milliseconds (optional)
     * @return The created note
     * @throws Exception if audio copy fails
     */
    suspend fun createImportedNote(
        title: String,
        sourceUri: Uri,
        extension: String = "m4a",
        durationMs: Long? = null
    ): RecordingNote {
        val noteId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        
        // Copy the audio file into app storage
        val audioFileName = audioFileManager.copyImportedAudio(sourceUri, noteId, extension)
        val audioFile = audioFileManager.getAudioFile(audioFileName)
        
        // Extract waveform data from imported audio
        val waveformData = waveformExtractor.extractWaveform(audioFile, targetSampleCount = 200)
            .getOrNull()
        
        val note = RecordingNote(
            id = noteId,
            createdAtEpochMs = now,
            title = title,
            audioFileName = audioFileName,
            durationMs = durationMs,
            source = NoteSource.IMPORTED,
            status = NoteStatus.NEW,
            transcriptText = null,
            summaryText = null,
            errorMessage = null,
            waveformData = waveformData
        )
        
        dao.insert(note.toEntity())
        return note
    }
    
    /**
     * Update the transcript for a note.
     */
    suspend fun updateTranscript(id: String, text: String, status: NoteStatus) {
        dao.updateTranscript(id, text, status.name)
    }
    
    /**
     * Update the summary for a note.
     */
    suspend fun updateSummary(id: String, text: String, status: NoteStatus) {
        dao.updateSummary(id, text, status.name)
    }
    
    /**
     * Update the status of a note.
     */
    suspend fun updateStatus(id: String, status: NoteStatus, errorMessage: String? = null) {
        dao.updateStatus(id, status.name, errorMessage)
    }
    
    /**
     * Delete a note and its associated audio file.
     */
    suspend fun deleteNote(id: String) {
        val note = dao.getById(id)
        if (note != null) {
            audioFileManager.deleteAudioFile(note.audioFileName)
            dao.deleteById(id)
        }
    }
    
    /**
     * Get the audio file for a note.
     */
    fun getAudioFile(note: RecordingNote): File {
        return audioFileManager.getAudioFile(note.audioFileName)
    }
    
    /**
     * Delete all notes and audio files (use with caution).
     */
    suspend fun deleteAllNotes() {
        audioFileManager.deleteAllAudioFiles()
        dao.deleteAll()
    }
    
    /**
     * Transcribe a note's audio file using Nexa ASR.
     * Updates the note's status and transcript text in the database.
     * Runs in background scope, independent of ViewModel lifecycle.
     * 
     * @param noteId The note ID to transcribe
     * @param language Language code for transcription (default: "en")
     */
    fun startTranscription(noteId: String, language: String = "en") {
        backgroundScope.launch {
            try {
                val note = dao.getById(noteId) ?: run {
                    Log.w(TAG, "Note not found for transcription")
                    return@launch
                }
                
                dao.updateStatus(noteId, NoteStatus.TRANSCRIBING.name, null)
                
                val audioFile = audioFileManager.getAudioFile(note.audioFileName)
                if (!audioFile.exists()) {
                    val error = "Audio file not found"
                    Log.e(TAG, error)
                    dao.updateStatus(noteId, NoteStatus.ERROR.name, error)
                    progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                    return@launch
                }
                
                val result = NexaAsrEngine.getInstance(context).transcribe(
                    audioPath = audioFile.absolutePath,
                    language = language
                )
                
                result.onSuccess { transcript ->
                    dao.updateTranscript(noteId, transcript, NoteStatus.DONE.name)
                    progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                }.onFailure { error ->
                    Log.e(TAG, "Transcription failed", error)
                    dao.updateStatus(noteId, NoteStatus.ERROR.name, "Transcription failed: ${error.message}")
                    progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during transcription", e)
                dao.updateStatus(noteId, NoteStatus.ERROR.name, e.message)
                progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
            }
        }
    }
    
    /**
     * Legacy method for compatibility. Prefer startTranscription() for new code.
     */
    @Deprecated("Use startTranscription() instead", ReplaceWith("startTranscription(noteId, language)"))
    suspend fun transcribeNote(noteId: String, language: String = "en") {
        startTranscription(noteId, language)
    }
    
    /**
     * Generate a SOAP summary for a note's transcript using Nexa LLM (streaming version).
     * Updates the note's summary text in the database.
     * Emits progress events and tokens during generation.
     * Manages progress lifecycle automatically.
     * 
     * @param noteId The note ID to generate summary for
     * @return Flow of SoapGenerationResult events
     */
    fun generateSummaryFlow(noteId: String): Flow<SoapGenerationResult> = channelFlow {
        val note = dao.getById(noteId) ?: run {
            val error = "Note not found for summary generation"
            Log.w(TAG, error)
            dao.updateStatus(noteId, NoteStatus.ERROR.name, error)
            send(SoapGenerationResult.Error(IllegalStateException(error)))
            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
            return@channelFlow
        }
        
        if (note.transcriptText.isNullOrEmpty()) {
            val error = "No transcript available for summary generation"
            Log.w(TAG, error)
            dao.updateStatus(noteId, NoteStatus.ERROR.name, error)
            send(SoapGenerationResult.Error(IllegalStateException(error)))
            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
            return@channelFlow
        }
        
        val summaryBuilder = StringBuilder()
        var hasError = false
        
        try {
            NexaLlmEngine.getInstance(context).generateSoapSummary(note.transcriptText).collect { result ->
                when (result) {
                    is SoapGenerationResult.Token -> {
                        summaryBuilder.append(result.text)
                        send(result)
                    }
                    is SoapGenerationResult.Completed -> {
                        dao.updateSummary(noteId, summaryBuilder.toString(), NoteStatus.DONE.name)
                        progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                        send(result)
                    }
                    is SoapGenerationResult.Error -> {
                        hasError = true
                        val errorMessage = "Summary generation failed: ${result.throwable.message}"
                        Log.e(TAG, errorMessage, result.throwable)
                        dao.updateStatus(noteId, NoteStatus.ERROR.name, errorMessage)
                        progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                        send(result)
                    }
                    is SoapGenerationResult.SummarizerStarted,
                    is SoapGenerationResult.SummarizerCompleted,
                    is SoapGenerationResult.SoapCreatorStarted -> {
                        send(result)
                    }
                }
            }
        } catch (e: Exception) {
            val errorMessage = "Unexpected error during summary generation: ${e.message}"
            Log.e(TAG, errorMessage, e)
            dao.updateStatus(noteId, NoteStatus.ERROR.name, errorMessage)
            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
            send(SoapGenerationResult.Error(e))
        }
    }
    
    /**
     * Start background summary generation for a note.
     * Runs in background scope and survives ViewModel lifecycle.
     * Updates note status to SUMMARIZING and handles completion/errors automatically.
     * Starts progress simulation based on transcript length.
     * 
     * @param noteId The note ID to generate summary for
     */
    fun startSummaryGeneration(noteId: String) {
        backgroundScope.launch {
            try {
                val note = dao.getById(noteId) ?: run {
                    Log.w(TAG, "Note not found for summary generation")
                    return@launch
                }
                
                if (note.transcriptText.isNullOrEmpty()) {
                    val error = "No transcript available for summary generation"
                    Log.w(TAG, error)
                    dao.updateStatus(noteId, NoteStatus.ERROR.name, error)
                    progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                    return@launch
                }
                
                dao.updateStatus(noteId, NoteStatus.SUMMARIZING.name, null)
                
                val transcriptLength = note.transcriptText.length
                val isShortTranscript = transcriptLength < NexaLlmEngine.SEGMENT_SIZE
                
                if (isShortTranscript) {
                    val promptLength = NexaLlmEngine.SOAP_SYSTEM_PROMPT.length +
                                      NexaLlmEngine.SOAP_USER_PREFIX.length
                    
                    progressManager.startSummarySinglePhaseProgress(
                        noteId = noteId,
                        transcriptLength = transcriptLength,
                        msPerChar = NexaLlmEngine.SOAP_CREATOR_MS_PER_CHAR,
                        promptLength = promptLength
                    )
                } else {
                    val promptLength = NexaLlmEngine.SECTION_SUMMARIZER_PROMPT.length +
                                      NexaLlmEngine.SUMMARIZER_USER_PREFIX.length
                    
                    progressManager.startSummaryPhase1Progress(
                        noteId = noteId,
                        transcriptLength = transcriptLength,
                        segmentSize = NexaLlmEngine.SEGMENT_SIZE,
                        msPerChar = NexaLlmEngine.SUMMARIZER_MS_PER_CHAR,
                        promptLength = promptLength
                    )
                }
                
                val summaryBuilder = StringBuilder()
                
                NexaLlmEngine.getInstance(context).generateSoapSummary(note.transcriptText).collect { result ->
                    when (result) {
                        is SoapGenerationResult.Token -> {
                            summaryBuilder.append(result.text)
                        }
                        is SoapGenerationResult.Completed -> {
                            dao.updateSummary(noteId, summaryBuilder.toString(), NoteStatus.DONE.name)
                            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                        }
                        is SoapGenerationResult.Error -> {
                            val errorMessage = "Summary generation failed: ${result.throwable.message}"
                            Log.e(TAG, errorMessage, result.throwable)
                            dao.updateStatus(noteId, NoteStatus.ERROR.name, errorMessage)
                            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                        }
                        is SoapGenerationResult.SummarizerCompleted -> {
                            val promptLength = NexaLlmEngine.SOAP_SYSTEM_PROMPT.length +
                                              NexaLlmEngine.SOAP_USER_PREFIX.length
                            
                            progressManager.startSummaryPhase2Progress(
                                noteId = noteId,
                                summaryLength = result.totalSummaryLength,
                                msPerChar = NexaLlmEngine.SOAP_CREATOR_MS_PER_CHAR,
                                promptLength = promptLength
                            )
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during summary generation", e)
                dao.updateStatus(noteId, NoteStatus.ERROR.name, e.message)
                progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
            }
        }
    }
    
    /**
     * Generate a SOAP summary for a note's transcript using Nexa LLM.
     * Updates the note's summary text in the database.
     * 
     * @param noteId The note ID to generate summary for
     * @return Result indicating success or failure
     */
    suspend fun generateSummary(noteId: String): Result<String> {
        return try {
            val note = dao.getById(noteId) ?: run {
                val error = "Note not found for summary generation"
                Log.w(TAG, error)
                return Result.failure(IllegalStateException(error))
            }
            
            if (note.transcriptText.isNullOrEmpty()) {
                val error = "No transcript available for summary generation"
                Log.w(TAG, error)
                return Result.failure(IllegalStateException(error))
            }
            
            val result = NexaLlmEngine.getInstance(context).generateSoapSummaryBlocking(
                transcript = note.transcriptText
            )
            
            result.onSuccess { summary ->
                dao.updateSummary(noteId, summary, NoteStatus.DONE.name)
            }.onFailure { error ->
                Log.e(TAG, "Summary generation failed", error)
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during summary generation", e)
            Result.failure(e)
        }
    }
    
    companion object {
        private const val TAG = "NotesRepository"
        @Volatile
        private var INSTANCE: NotesRepository? = null
        
        fun getInstance(context: Context): NotesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotesRepository(
                    database = AppDatabase.getInstance(context),
                    audioFileManager = AudioFileManager.getInstance(context),
                    context = context.applicationContext
                ).also { INSTANCE = it }
            }
        }
    }
}
