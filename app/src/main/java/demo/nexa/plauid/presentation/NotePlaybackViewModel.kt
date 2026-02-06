package demo.nexa.plauid.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import demo.nexa.plauid.audio.AudioPlayer
import demo.nexa.plauid.common.BackgroundProgressManager
import demo.nexa.plauid.data.repository.NotesRepository
import demo.nexa.plauid.domain.model.NoteStatus
import demo.nexa.plauid.domain.model.RecordingNote
import demo.nexa.plauid.llm.NexaLlmEngine
import demo.nexa.plauid.llm.SoapGenerationResult
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
    
    /**
     * Load a note by ID and prepare for playback.
     * Observes the note for changes (e.g., transcription status updates).
     */
    fun loadNote(noteId: String) {
        viewModelScope.launch {
            try {
                _uiState.value = PlaybackUiState.Loading
                
                // Cancel any existing note observation to prevent stale data
                noteObservationJob?.cancel()
                noteObservationJob = null
                
                // First, load note once to prepare audio player
                val initialNote = withContext(Dispatchers.IO) {
                    repository.getNoteById(noteId)
                }
                
                if (initialNote == null) {
                    _uiState.value = PlaybackUiState.Error("Note not found")
                    return@launch
                }
                
                // Get audio file and prepare player
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
                    
                    // Now observe the note for reactive updates (transcription status, error messages, etc.)
                    noteObservationJob = viewModelScope.launch {
                        var previousStatus: NoteStatus? = null
                        
                        repository.observeNoteById(noteId).collect { updatedNote ->
                            if (updatedNote != null) {
                                // Detect transcription status changes
                                val statusChanged = previousStatus != updatedNote.status
                                
                                if (statusChanged) {
                                    when (updatedNote.status) {
                                        NoteStatus.TRANSCRIBING -> {
                                            // Start background progress (only if not already running)
                                            if (progressManager.getCurrentProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION) == null) {
                                                progressManager.startTranscriptionProgress(
                                                    noteId = noteId,
                                                    audioDurationMs = duration,
                                                    rtf = ASR_MODEL_RTF
                                                )
                                            }
                                            // Start observing progress
                                            startObservingTranscriptionProgress(noteId)
                                        }
                                        NoteStatus.SUMMARIZING -> {
                                            // Start observing summary progress
                                            // Progress simulation is already started by repository.startSummaryGeneration()
                                            startObservingSummaryProgress(noteId)
                                        }
                                        NoteStatus.DONE, NoteStatus.ERROR -> {
                                            // Stop observing (progress is stopped by repository)
                                            stopObservingTranscriptionProgress()
                                            stopObservingSummaryProgress()
                                        }
                                        else -> {}
                                    }
                                    previousStatus = updatedNote.status
                                } else if (updatedNote.status == NoteStatus.TRANSCRIBING) {
                                    // Resume observing if we return to a note that's already transcribing
                                    startObservingTranscriptionProgress(noteId)
                                } else if (updatedNote.status == NoteStatus.SUMMARIZING) {
                                    // Resume observing if we return to a note that's already summarizing
                                    startObservingSummaryProgress(noteId)
                                }
                                
                                // Update state with latest note data
                                val currentState = _uiState.value
                                if (currentState is PlaybackUiState.Ready) {
                                    // When updating existing Ready state, preserve/restore progress if transcribing/summarizing
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
                                    
                                    _uiState.value = currentState.copy(
                                        note = updatedNote,
                                        transcriptionProgress = currentTranscriptionProgress,
                                        summaryProgress = currentSummaryProgress,
                                        isGeneratingSummary = isGeneratingSummary
                                    )
                                } else {
                                    // First time setting Ready state
                                    // Check if there's existing progress for transcription or summary
                                    val existingTranscriptionProgress = progressManager.getCurrentProgress(noteId, BackgroundProgressManager.ProgressType.TRANSCRIPTION)
                                    val existingSummaryProgress = progressManager.getCurrentProgress(noteId, BackgroundProgressManager.ProgressType.SUMMARY)
                                    val isGeneratingSummary = updatedNote.status == NoteStatus.SUMMARIZING
                                    
                                    _uiState.value = PlaybackUiState.Ready(
                                        note = updatedNote,
                                        isPlaying = false,
                                        currentPositionMs = 0L,
                                        durationMs = duration,
                                        waveformAmplitudes = updatedNote.waveformData ?: emptyList(),
                                        transcriptionProgress = if (updatedNote.status == NoteStatus.TRANSCRIBING) existingTranscriptionProgress else null,
                                        isGeneratingSummary = isGeneratingSummary,
                                        summaryProgress = if (isGeneratingSummary) existingSummaryProgress else null
                                    )
                                }
                            }
                        }
                    }
                    
                    // Set completion listener
                    audioPlayer.setOnCompletionListener {
                        onPlaybackComplete()
                    }
                    
                    // Auto-start transcription if note hasn't been transcribed yet or previous attempt failed
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
    
    /**
     * Toggle play/pause.
     */
    fun togglePlayPause() {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) return
        
        if (currentState.isPlaying) {
            pause()
        } else {
            play()
        }
    }
    
    /**
     * Start playback.
     */
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
    
    /**
     * Pause playback.
     */
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
    
    /**
     * Seek to a specific position.
     */
    fun seekTo(positionMs: Long) {
        val currentState = _uiState.value
        if (currentState !is PlaybackUiState.Ready) return
        if (isScrubbing) {
            // If we're scrubbing, treat this as preview only (no player seek).
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

        // Stop polling so it doesn't fight the user's drag.
        stopProgressUpdates()

        // Pause playback while scrubbing for deterministic position feedback.
        if (wasPlayingBeforeScrub) {
            audioPlayer.pause()
        }

        _uiState.update {
            if (it is PlaybackUiState.Ready) it.copy(isPlaying = false) else it
        }
    }

    /**
     * Preview scrub position (UI-only). Does NOT seek the underlying player.
     */
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
        
        // Seek the player to the final position
        audioPlayer.seekTo(clampedPosition)
        
        // Update state with the committed position
        _uiState.update {
            if (it is PlaybackUiState.Ready) it.copy(currentPositionMs = clampedPosition) else it
        }

        isScrubbing = false

        // Resume playback if it was playing before
        if (wasPlayingBeforeScrub) {
            audioPlayer.play()
            _uiState.update {
                if (it is PlaybackUiState.Ready) it.copy(isPlaying = true) else it
            }
            startProgressUpdates()
        } else {
            // Not resuming playback, but we should still reflect the seek position
            // The state is already updated above
        }
        wasPlayingBeforeScrub = false
    }
    
    /**
     * Start updating playback progress.
     */
    private fun startProgressUpdates() {
        stopProgressUpdates() // Cancel any existing job
        
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
                
                delay(50) // Update every 50ms for smooth UI
            }
        }
    }
    
    /**
     * Stop progress updates.
     */
    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }
    
    /**
     * Start observing transcription progress from BackgroundProgressManager.
     */
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
    
    /**
     * Stop observing transcription progress.
     */
    private fun stopObservingTranscriptionProgress() {
        transcriptionProgressObserverJob?.cancel()
        transcriptionProgressObserverJob = null
        
        // Clear progress from state
        _uiState.update {
            if (it is PlaybackUiState.Ready) {
                it.copy(transcriptionProgress = null)
            } else {
                it
            }
        }
    }
    
    /**
     * Start observing summary progress from BackgroundProgressManager.
     */
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
    
    /**
     * Stop observing summary progress.
     */
    private fun stopObservingSummaryProgress() {
        summaryProgressObserverJob?.cancel()
        summaryProgressObserverJob = null
        
        // Clear progress from state
        _uiState.update {
            if (it is PlaybackUiState.Ready) {
                it.copy(summaryProgress = null)
            } else {
                it
            }
        }
    }
    
    /**
     * Stop summary progress simulation (no longer needed with BackgroundProgressManager).
     * Kept for backward compatibility.
     */
    private fun stopSummaryProgressSimulation() {
        // No-op: progress is managed by BackgroundProgressManager
    }
    
    /**
     * Handle playback completion (reached end).
     */
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
        
        // Reset to beginning
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
        
        // Check if transcript is available
        if (currentState.note.transcriptText.isNullOrEmpty()) {
            return
        }
        
        val noteId = currentState.note.id
        
        // Set generating flag (will be updated when status changes to SUMMARIZING)
        _uiState.update {
            if (it is PlaybackUiState.Ready) {
                it.copy(isGeneratingSummary = true, summaryProgress = 0, summaryError = null)
            } else {
                it
            }
        }
        
        // Start background summary generation
        // Progress simulation is started automatically by the repository
        repository.startSummaryGeneration(noteId)
    }
    
    /**
     * Clear summary error message.
     */
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
        
        // Cancel note observation
        noteObservationJob?.cancel()
        
        // Stop observing progress (but don't stop background progress itself)
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
