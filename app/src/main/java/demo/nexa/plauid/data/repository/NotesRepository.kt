package demo.nexa.plauid.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import demo.nexa.plauid.asr.NexaAsrEngine
import demo.nexa.plauid.audio.WaveformExtractor
import demo.nexa.plauid.common.BackgroundProgressManager
import demo.nexa.plauid.data.audio.AudioFileManager
import demo.nexa.plauid.data.local.AppDatabase
import demo.nexa.plauid.data.mapper.toDomain
import demo.nexa.plauid.data.mapper.toEntity
import demo.nexa.plauid.domain.model.NoteSource
import demo.nexa.plauid.domain.model.NoteStatus
import demo.nexa.plauid.domain.model.RecordingNote
import demo.nexa.plauid.llm.NexaLlmEngine
import demo.nexa.plauid.llm.SoapGenerationResult
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
            // Delete audio file first
            audioFileManager.deleteAudioFile(note.audioFileName)
            // Then delete database record
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
                // Get the note
                val note = dao.getById(noteId) ?: run {
                    Log.w(TAG, "Note not found for transcription: $noteId")
                    return@launch
                }
                
                // Update status to TRANSCRIBING
                dao.updateStatus(noteId, NoteStatus.TRANSCRIBING.name, null)
                
                // Get audio file path
                val audioFile = audioFileManager.getAudioFile(note.audioFileName)
                if (!audioFile.exists()) {
                    val error = "Audio file not found: ${audioFile.absolutePath}"
                    Log.e(TAG, error)
                    dao.updateStatus(noteId, NoteStatus.ERROR.name, error)
                    progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                    return@launch
                }
                
                // Get audio duration for progress tracking
                // Note: waveformData contains duration info, but we can estimate from file
                // For now, use a rough estimate: file size / (sample_rate * bytes_per_sample * channels)
                // Typical: 16kHz, 16-bit (2 bytes), mono = 32000 bytes/sec
                val audioDurationMs = (audioFile.length() * 1000L / 32000L).coerceAtLeast(1000L)
                
                // Transcribe using Nexa ASR
                val result = NexaAsrEngine.getInstance(context).transcribe(
                    audioPath = audioFile.absolutePath,
                    language = language
                )
                
                result.onSuccess { transcript ->
                    dao.updateTranscript(noteId, transcript, NoteStatus.DONE.name)
                    progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                }.onFailure { error ->
                    val errorMessage = "Transcription failed: ${error.message}"
                    Log.e(TAG, errorMessage, error)
                    dao.updateStatus(noteId, NoteStatus.ERROR.name, errorMessage)
                    progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                }
                
            } catch (e: Exception) {
                val errorMessage = "Unexpected error during transcription: ${e.message}"
                Log.e(TAG, errorMessage, e)
                dao.updateStatus(noteId, NoteStatus.ERROR.name, errorMessage)
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
        // Get the note
        val note = dao.getById(noteId) ?: run {
            val error = "Note not found for summary generation: $noteId"
            Log.w(TAG, error)
            send(SoapGenerationResult.Error(IllegalStateException(error)))
            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
            return@channelFlow
        }
        
        // Check if transcript is available
        if (note.transcriptText.isNullOrEmpty()) {
            val error = "No transcript available for summary generation"
            Log.w(TAG, error)
            send(SoapGenerationResult.Error(IllegalStateException(error)))
            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
            return@channelFlow
        }
        
        Log.d(TAG, "Generating SOAP summary for note: $noteId")
        
        val summaryBuilder = StringBuilder()
        var hasError = false
        
        try {
            // Generate summary using Nexa LLM (streaming)
            NexaLlmEngine.getInstance(context).generateSoapSummary(note.transcriptText).collect { result ->
                when (result) {
                    is SoapGenerationResult.Token -> {
                        summaryBuilder.append(result.text)
                        send(result)  // Forward token
                    }
                    is SoapGenerationResult.Completed -> {
                        Log.d(TAG, "Summary generated successfully (${summaryBuilder.length} chars)")
                        dao.updateSummary(noteId, summaryBuilder.toString(), NoteStatus.DONE.name)
                        progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                        send(result)  // Forward completion
                    }
                    is SoapGenerationResult.Error -> {
                        hasError = true
                        val errorMessage = "Summary generation failed: ${result.throwable.message}"
                        Log.e(TAG, errorMessage, result.throwable)
                        progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                        send(result)  // Forward error
                    }
                    // Forward progress events
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
                // Get the note
                val note = dao.getById(noteId) ?: run {
                    Log.w(TAG, "Note not found for summary generation: $noteId")
                    return@launch
                }
                
                // Check if transcript is available
                if (note.transcriptText.isNullOrEmpty()) {
                    val error = "No transcript available for summary generation"
                    Log.w(TAG, error)
                    dao.updateStatus(noteId, NoteStatus.ERROR.name, error)
                    progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                    return@launch
                }
                
                // Update status to SUMMARIZING
                dao.updateStatus(noteId, NoteStatus.SUMMARIZING.name, null)
                
                // Start progress simulation based on transcript length
                val transcriptLength = note.transcriptText.length
                val isShortTranscript = transcriptLength < NexaLlmEngine.SEGMENT_SIZE
                
                if (isShortTranscript) {
                    // Short transcript: Single phase (SOAP creator only)
                    val promptLength = NexaLlmEngine.SOAP_SYSTEM_PROMPT.length +
                                      NexaLlmEngine.SOAP_USER_PREFIX.length
                    
                    progressManager.startSummarySinglePhaseProgress(
                        noteId = noteId,
                        transcriptLength = transcriptLength,
                        msPerChar = NexaLlmEngine.SOAP_CREATOR_MS_PER_CHAR,
                        promptLength = promptLength
                    )
                } else {
                    // Long transcript: Start Phase 1 (Section summarization)
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
                
                Log.d(TAG, "Starting background summary generation for note: $noteId")
                
                val summaryBuilder = StringBuilder()
                
                // Generate summary using Nexa LLM (streaming)
                NexaLlmEngine.getInstance(context).generateSoapSummary(note.transcriptText).collect { result ->
                    when (result) {
                        is SoapGenerationResult.Token -> {
                            summaryBuilder.append(result.text)
                        }
                        is SoapGenerationResult.Completed -> {
                            Log.d(TAG, "Background summary generated successfully (${summaryBuilder.length} chars)")
                            dao.updateSummary(noteId, summaryBuilder.toString(), NoteStatus.DONE.name)
                            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                        }
                        is SoapGenerationResult.Error -> {
                            val errorMessage = "Summary generation failed: ${result.throwable.message}"
                            Log.e(TAG, errorMessage, result.throwable)
                            dao.updateStatus(noteId, NoteStatus.ERROR.name, errorMessage)
                            progressManager.stopProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                        }
                        // Handle phase transition for long transcripts
                        is SoapGenerationResult.SummarizerCompleted -> {
                            // Transition to phase 2 with actual summary length
                            val promptLength = NexaLlmEngine.SOAP_SYSTEM_PROMPT.length +
                                              NexaLlmEngine.SOAP_USER_PREFIX.length
                            
                            progressManager.startSummaryPhase2Progress(
                                noteId = noteId,
                                summaryLength = result.totalSummaryLength,
                                msPerChar = NexaLlmEngine.SOAP_CREATOR_MS_PER_CHAR,
                                promptLength = promptLength
                            )
                        }
                        // Other progress events don't need handling
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                val errorMessage = "Unexpected error during background summary generation: ${e.message}"
                Log.e(TAG, errorMessage, e)
                dao.updateStatus(noteId, NoteStatus.ERROR.name, errorMessage)
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
            // Get the note
            val note = dao.getById(noteId) ?: run {
                val error = "Note not found for summary generation: $noteId"
                Log.w(TAG, error)
                return Result.failure(IllegalStateException(error))
            }
            
            // Check if transcript is available
            if (note.transcriptText.isNullOrEmpty()) {
                val error = "No transcript available for summary generation"
                Log.w(TAG, error)
                return Result.failure(IllegalStateException(error))
            }
            
            Log.d(TAG, "Generating SOAP summary for note: $noteId")
            
            // Generate summary using Nexa LLM
            val result = NexaLlmEngine.getInstance(context).generateSoapSummaryBlocking(
                transcript = note.transcriptText
            )
            
            result.onSuccess { summary ->
                Log.d(TAG, "Summary generated successfully (${summary.length} chars)")
                dao.updateSummary(noteId, summary, NoteStatus.DONE.name)
            }.onFailure { error ->
                val errorMessage = "Summary generation failed: ${error.message}"
                Log.e(TAG, errorMessage, error)
            }
            
            result
        } catch (e: Exception) {
            val errorMessage = "Unexpected error during summary generation: ${e.message}"
            Log.e(TAG, errorMessage, e)
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
