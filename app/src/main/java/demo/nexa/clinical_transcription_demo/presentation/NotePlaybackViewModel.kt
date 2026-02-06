package demo.nexa.clinical_transcription_demo.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import demo.nexa.clinical_transcription_demo.audio.AudioPlayer
import demo.nexa.clinical_transcription_demo.common.BackgroundProgressManager
import demo.nexa.clinical_transcription_demo.data.repository.NotesRepository
import demo.nexa.clinical_transcription_demo.domain.model.NoteStatus
import demo.nexa.clinical_transcription_demo.domain.model.RecordingNote
import demo.nexa.clinical_transcription_demo.llm.NexaLlmEngine
import demo.nexa.clinical_transcription_demo.llm.SoapGenerationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for note detail screen with audio playback.
 * Manages playback state, progress updates, and waveform data.
 */
class NotePlaybackViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = NotesRepository.getInstance(application)
    private val audioPlayer = AudioPlayer()
    private val progressManager = BackgroundProgressManager.getInstance()
    
    private val _uiState = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading)
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    
    private var progressUpdateJob: Job? = null
    private var noteObservationJob: Job? = null
    private var transcriptionProgressObserverJob: Job? = null
    private var summaryProgressObserverJob: Job? = null
    private var isScrubbing: Boolean = false
    private var wasPlayingBeforeScrub: Boolean = false
    
    companion object {
        // Average Real-Time Factor (RTF) for the ASR model
        // RTF = inference_time / audio_duration
        // 0.2 means the model processes audio 5x faster than real-time
        private const val ASR_MODEL_RTF = 0.2
    }
    
    fun loadNote(noteId: String) {
        viewModelScope.launch {
            try {
                _uiState.value = PlaybackUiState.Loading
                
                noteObservationJob?.cancel()
                noteObservationJob = null
                
                val initialNote = withContext(Dispatchers.IO) {
                    repository.getNoteById(noteId)
                }
                
                if (initialNote == null) {
                    _uiState.value = PlaybackUiState.Error("Note not found")
                    return@launch
                }
                
                val audioFile = repository.getAudioFile(initialNote)
                
                if (!audioFile.exists()) {
                    _uiState.value = PlaybackUiState.Error("Audio file not found")
                    return@launch
                }
                
                val prepareResult = withContext(Dispatchers.IO) {
                    audioPlayer.prepare(audioFile)
                }
                
                prepareResult.onSuccess {
                    val duration = audioPlayer.getDuration()
                    
                    noteObservationJob = viewModelScope.launch {
                        var previousStatus: NoteStatus? = null
                        
                        repository.observeNoteById(noteId).collect { updatedNote ->
                            if (updatedNote != null) {
                                val statusChanged = previousStatus != updatedNote.status
                                
                                if (statusChanged) {
                                    when (updatedNote.status) {
                                        NoteStatus.TRANSCRIBING -> {
                                            if (progressManager.getCurrentProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION) == null) {
                                                progressManager.startTranscriptionProgress(
                                                    noteId = noteId,
                                                    audioDurationMs = duration,
                                                    rtf = ASR_MODEL_RTF
                                                )
                                            }
                                            startObservingTranscriptionProgress(noteId)
                                        }
                                        NoteStatus.SUMMARIZING -> {
                                            startObservingSummaryProgress(noteId)
                                        }
                                        NoteStatus.DONE, NoteStatus.ERROR -> {
                                            stopObservingTranscriptionProgress()
                                            stopObservingSummaryProgress()
                                        }
                                        else -> {}
                                    }
                                    previousStatus = updatedNote.status
                                } else if (updatedNote.status == NoteStatus.TRANSCRIBING) {
                                    startObservingTranscriptionProgress(noteId)
                                } else if (updatedNote.status == NoteStatus.SUMMARIZING) {
                                    startObservingSummaryProgress(noteId)
                                }
                                
                                val currentState = _uiState.value
                                if (currentState is PlaybackUiState.Ready) {
                                    val currentTranscriptionProgress = if (updatedNote.status == NoteStatus.TRANSCRIBING) {
                                        progressManager.getCurrentProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                                    } else {
                                        null
                                    }
                                    val currentSummaryProgress = if (updatedNote.status == NoteStatus.SUMMARIZING) {
                                        progressManager.getCurrentProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                                    } else {
                                        null
                                    }
                                    val isGeneratingSummary = updatedNote.status == NoteStatus.SUMMARIZING
                                    
                                    val summaryError = if (updatedNote.status == NoteStatus.ERROR && updatedNote.summaryText == null) {
                                        updatedNote.errorMessage
                                    } else {
                                        null
                                    }
                                    
                                    _uiState.value = currentState.copy(
                                        note = updatedNote,
                                        transcriptionProgress = currentTranscriptionProgress,
                                        summaryProgress = currentSummaryProgress,
                                        isGeneratingSummary = isGeneratingSummary,
                                        summaryError = summaryError
                                    )
                                } else {
                                    val existingTranscriptionProgress = progressManager.getCurrentProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                                    val existingSummaryProgress = progressManager.getCurrentProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                                    val isGeneratingSummary = updatedNote.status == NoteStatus.SUMMARIZING
                                    
                                    // Show error message if status is ERROR and there's no summary yet
                                    val summaryError = if (updatedNote.status == NoteStatus.ERROR && updatedNote.summaryText == null) {
                                        updatedNote.errorMessage
                                    } else {
                                        null
                                    }
                                    
                                    _uiState.value = PlaybackUiState.Ready(
                                        note = updatedNote,
                                        isPlaying = false,
                                        currentPositionMs = 0L,
                                        durationMs = duration,
                                        waveformAmplitudes = updatedNote.waveformData ?: emptyList(),
                                        transcriptionProgress = if (updatedNote.status == NoteStatus.TRANSCRIBING) existingTranscriptionProgress else null,
                                        isGeneratingSummary = isGeneratingSummary,
                                        summaryProgress = if (isGeneratingSummary) existingSummaryProgress else null,
                                        summaryError = summaryError
                                    )
                                }
                            }
                        }
                    }
                    
                    audioPlayer.setOnCompletionListener {
                        onPlaybackComplete()
                    }
                    
                    if (initialNote.transcriptText == null && 
                        (initialNote.status == NoteStatus.NEW || initialNote.status == NoteStatus.ERROR)) {
                        repository.startTranscription(noteId)
                    }
                    
                }.onFailure { error ->
                    _uiState.value = PlaybackUiState.Error("Failed to load audio: ${error.message}")
                }
                
            } catch (e: Exception) {
                _uiState.value = PlaybackUiState.Error("Error loading note: ${e.message}")
            }
        }
    }
    
    fun togglePlayPause() {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) return
        
        if (currentState.isPlaying) {
            pause()
        } else {
            play()
        }
    }
    
    private fun play() {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) return
        
        audioPlayer.play()
        _uiState.update { 
            if (it is PlaybackUiState.Ready) {
                it.copy(isPlaying = true)
            } else {
                it
            }
        }
        
        startProgressUpdates()
    }
    
    private fun pause() {
        audioPlayer.pause()
        _uiState.update { 
            if (it is PlaybackUiState.Ready) {
                it.copy(isPlaying = false)
            } else {
                it
            }
        }
        
        stopProgressUpdates()
    }
    
    fun seekTo(positionMs: Long) {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) return
        if (isScrubbing) {
            previewScrub(positionMs)
            return
        }
        
        val clampedPosition = positionMs.coerceIn(0L, currentState.durationMs)
        
        audioPlayer.seekTo(clampedPosition)
        _uiState.update { 
            if (it is PlaybackUiState.Ready) {
                it.copy(currentPositionMs = clampedPosition)
            } else {
                it
            }
        }
    }

    /**
     * Begin a scrub gesture. We pause progress polling and (if needed) pause playback,
     * so the UI position can follow the user's finger precisely.
     */
    fun beginScrub() {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) return
        if (isScrubbing) return

        isScrubbing = true
        wasPlayingBeforeScrub = currentState.isPlaying

        stopProgressUpdates()

        if (wasPlayingBeforeScrub) {
            audioPlayer.pause()
        }

        _uiState.update {
            if (it is PlaybackUiState.Ready) it.copy(isPlaying = false) else it
        }
    }

    fun previewScrub(positionMs: Long) {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) return

        val clampedPosition = positionMs.coerceIn(0L, currentState.durationMs)
        _uiState.update {
            if (it is PlaybackUiState.Ready) it.copy(currentPositionMs = clampedPosition) else it
        }
    }

    /**
     * End scrub gesture and commit seek to player. If playback was active before scrubbing,
     * resumes playback and progress polling.
     */
    fun endScrub(commitPositionMs: Long) {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) return
        if (!isScrubbing) return

        val clampedPosition = commitPositionMs.coerceIn(0L, currentState.durationMs)
        
        audioPlayer.seekTo(clampedPosition)
        
        _uiState.update {
            if (it is PlaybackUiState.Ready) it.copy(currentPositionMs = clampedPosition) else it
        }

        isScrubbing = false

        if (wasPlayingBeforeScrub) {
            audioPlayer.play()
            _uiState.update {
                if (it is PlaybackUiState.Ready) it.copy(isPlaying = true) else it
            }
            startProgressUpdates()
        }
        wasPlayingBeforeScrub = false
    }
    
    private fun startProgressUpdates() {
        stopProgressUpdates()
        
        progressUpdateJob = viewModelScope.launch {
            while (true) {
                val position = audioPlayer.getCurrentPosition()
                
                _uiState.update { 
                    if (it is PlaybackUiState.Ready) {
                        it.copy(currentPositionMs = position)
                    } else {
                        it
                    }
                }
                
                delay(50)
            }
        }
    }
    
    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }
    
    private fun startObservingTranscriptionProgress(noteId: String) {
        transcriptionProgressObserverJob?.cancel()
        
        val progressFlow = progressManager.observeProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION) ?: return
        
        transcriptionProgressObserverJob = viewModelScope.launch {
            progressFlow.collect { progress ->
                _uiState.update {
                    if (it is PlaybackUiState.Ready) {
                        it.copy(transcriptionProgress = progress)
                    } else {
                        it
                    }
                }
            }
        }
    }
    
    private fun stopObservingTranscriptionProgress() {
        transcriptionProgressObserverJob?.cancel()
        transcriptionProgressObserverJob = null
        
        _uiState.update {
            if (it is PlaybackUiState.Ready) {
                it.copy(transcriptionProgress = null)
            } else {
                it
            }
        }
    }
    
    private fun startObservingSummaryProgress(noteId: String) {
        summaryProgressObserverJob?.cancel()
        
        val progressFlow = progressManager.observeProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY) ?: return
        
        summaryProgressObserverJob = viewModelScope.launch {
            progressFlow.collect { progress ->
                _uiState.update {
                    if (it is PlaybackUiState.Ready) {
                        it.copy(summaryProgress = progress)
                    } else {
                        it
                    }
                }
            }
        }
    }
    
    private fun stopObservingSummaryProgress() {
        summaryProgressObserverJob?.cancel()
        summaryProgressObserverJob = null
        
        _uiState.update {
            if (it is PlaybackUiState.Ready) {
                it.copy(summaryProgress = null)
            } else {
                it
            }
        }
    }
    
    private fun stopSummaryProgressSimulation() {
        // No-op: progress is managed by BackgroundProgressManager
    }
    
    private fun onPlaybackComplete() {
        _uiState.update { 
            if (it is PlaybackUiState.Ready) {
                it.copy(
                    isPlaying = false,
                    currentPositionMs = 0L
                )
            } else {
                it
            }
        }
        
        stopProgressUpdates()
        audioPlayer.seekTo(0)
    }
    
    /**
     * Generate a SOAP summary for the current note's transcript.
     * Starts background generation that survives ViewModel lifecycle.
     * Progress is managed by the repository and BackgroundProgressManager.
     */
    fun generateSummary() {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) {
            return
        }
        
        if (currentState.note.transcriptText.isNullOrEmpty()) {
            return
        }
        
        val noteId = currentState.note.id
        
        _uiState.update {
            if (it is PlaybackUiState.Ready) {
                it.copy(isGeneratingSummary = true, summaryProgress = 0, summaryError = null)
            } else {
                it
            }
        }
        
        repository.startSummaryGeneration(noteId)
    }
    
    fun clearSummaryError() {
        _uiState.update {
            if (it is PlaybackUiState.Ready) {
                it.copy(summaryError = null)
            } else {
                it
            }
        }
    }
    
    /**
     * Clean up resources when ViewModel is destroyed.
     * Note: BackgroundProgressManager continues running for background progress.
     * Background summary generation also continues running in repository's backgroundScope.
     */
    override fun onCleared() {
        super.onCleared()
        stopProgressUpdates()
        noteObservationJob?.cancel()
        stopObservingTranscriptionProgress()
        stopObservingSummaryProgress()
        audioPlayer.release()
    }
}

/**
 * UI state for playback screen.
 */
sealed class PlaybackUiState {
    object Loading : PlaybackUiState()
    
    data class Ready(
        val note: RecordingNote,
        val isPlaying: Boolean,
        val currentPositionMs: Long,
        val durationMs: Long,
        val waveformAmplitudes: List<Float>,
        val transcriptionProgress: Int? = null,  // 0-100 if transcribing, null otherwise
        val isGeneratingSummary: Boolean = false,  // true if summary generation is in progress
        val summaryProgress: Int? = null,  // 0-100 if generating summary, null otherwise
        val summaryError: String? = null  // Error message if summary generation failed
    ) : PlaybackUiState()
    
    data class Error(val message: String) : PlaybackUiState()
}
