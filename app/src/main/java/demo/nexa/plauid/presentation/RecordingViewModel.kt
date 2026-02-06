package demo.nexa.plauid.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import demo.nexa.plauid.audio.AudioRecorder
import demo.nexa.plauid.audio.AudioTranscoder
import demo.nexa.plauid.common.formatDateForFilename
import demo.nexa.plauid.data.audio.AudioFileManager
import demo.nexa.plauid.data.repository.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel for the recording screen.
 * Manages recording state, timer, and waveform data.
 */
class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = NotesRepository.getInstance(application)
    private val audioFileManager = AudioFileManager.getInstance(application)
    private val audioRecorder = AudioRecorder(application)
    private val audioTranscoder = AudioTranscoder.getInstance()
    
    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()
    
    private var timerJob: Job? = null
    private var amplitudeSamplingJob: Job? = null
    private var recordingNoteId: String? = null // Single UUID for both file and DB
    
    /**
     * Start recording audio.
     * Initializes MediaRecorder, starts timer, and begins amplitude sampling.
     * Generates a single UUID used for both file naming and DB record.
     */
    fun startRecording() {
        if (_uiState.value.isRecording) return
        
        viewModelScope.launch {
            try {
                // Generate single ID for this recording (used for both file and DB)
                val noteId = UUID.randomUUID().toString()
                recordingNoteId = noteId
                
                val outputFile = audioFileManager.createRecordingFile(noteId, "m4a")
                
                // Start recording
                val result = audioRecorder.startRecording(outputFile)
                
                result.onSuccess {
                    _uiState.update { it.copy(isRecording = true, errorMessage = null) }
                    startTimer()
                    startAmplitudeSampling()
                }.onFailure { error ->
                    recordingNoteId = null
                    _uiState.update { 
                        it.copy(
                            isRecording = false,
                            errorMessage = "Failed to start recording: ${error.message}"
                        )
                    }
                }
                
            } catch (e: Exception) {
                recordingNoteId = null
                _uiState.update { 
                    it.copy(
                        isRecording = false,
                        errorMessage = "Error starting recording: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Discard the current recording without saving.
     * Stops recording, deletes audio files, and cleans up.
     */
    fun discardRecording() {
        if (!_uiState.value.isRecording) return
        
        val noteId = recordingNoteId
        
        // Stop timer and sampling immediately
        timerJob?.cancel()
        amplitudeSamplingJob?.cancel()
        
        viewModelScope.launch {
            try {
                // Stop recording on main thread (MediaRecorder requirement)
                val result = audioRecorder.stopRecording()
                
                result.onSuccess { m4aFile ->
                    // Delete the audio file
                    withContext(Dispatchers.IO) {
                        m4aFile.delete()
                    }
                    
                    _uiState.update { 
                        it.copy(
                            isRecording = false,
                            isProcessing = false
                        )
                    }
                    recordingNoteId = null
                    
                }.onFailure { error ->
                    // Even if stopping failed, reset state
                    _uiState.update { 
                        it.copy(
                            isRecording = false,
                            isProcessing = false
                        )
                    }
                    recordingNoteId = null
                }
                
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isRecording = false,
                        isProcessing = false
                    )
                }
                recordingNoteId = null
            }
        }
    }
    
    /**
     * Stop recording and save to repository.
     * Converts M4A to WAV immediately after recording stops.
     * All heavy operations run on IO dispatcher to avoid blocking UI.
     */
    fun stopRecording() {
        if (!_uiState.value.isRecording) return
        
        val noteId = recordingNoteId
        if (noteId == null) {
            _uiState.update { 
                it.copy(
                    isRecording = false,
                    errorMessage = "Recording ID not found"
                )
            }
            return
        }
        
        // Stop timer and sampling immediately
        timerJob?.cancel()
        amplitudeSamplingJob?.cancel()
        
        // Set processing state to show loading UI
        _uiState.update { it.copy(isProcessing = true) }
        
        viewModelScope.launch {
            try {
                // Stop recording on main thread (MediaRecorder requirement)
                val result = audioRecorder.stopRecording()
                
                result.onSuccess { m4aFile ->
                    // All heavy work on IO dispatcher
                    withContext(Dispatchers.IO) {
                        val wavFile = audioFileManager.createWavFile(noteId)
                        
                        val conversionResult = audioTranscoder.convertToWav(
                            sourceFile = m4aFile,
                            outputFile = wavFile,
                            deleteSource = true // Delete M4A after successful conversion
                        )
                        
                        conversionResult.onSuccess { audioInfo ->
                            // Validate sample rate for ASR compatibility
                            val expectedSampleRate = 16000
                            if (audioInfo.sampleRate != expectedSampleRate) {
                                android.util.Log.w(
                                    "RecordingViewModel",
                                    "WAV sample rate is ${audioInfo.sampleRate}Hz, expected ${expectedSampleRate}Hz"
                                )
                            }
                            
                            // Generate title with timestamp
                            val title = generateRecordingTitle()
                            val duration = _uiState.value.elapsedTimeMs
                            
                            // Save to repository (uses same noteId for DB record)
                            repository.createRecordedNote(
                                id = noteId,
                                title = title,
                                audioFile = audioInfo.file,
                                durationMs = duration
                            )
                            
                            withContext(Dispatchers.Main) {
                                _uiState.update { 
                                    it.copy(
                                        isRecording = false,
                                        isProcessing = false,
                                        recordingSaved = true
                                    )
                                }
                                recordingNoteId = null
                            }
                            
                        }.onFailure { error ->
                            // Conversion failed, clean up
                            m4aFile.delete()
                            withContext(Dispatchers.Main) {
                                _uiState.update { 
                                    it.copy(
                                        isRecording = false,
                                        isProcessing = false,
                                        errorMessage = "Failed to convert recording: ${error.message}"
                                    )
                                }
                                recordingNoteId = null
                            }
                        }
                    }
                    
                }.onFailure { error ->
                    _uiState.update { 
                        it.copy(
                            isRecording = false,
                            errorMessage = "Failed to save recording: ${error.message}"
                        )
                    }
                    recordingNoteId = null
                }
                
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isRecording = false,
                        errorMessage = "Error stopping recording: ${e.message}"
                    )
                }
                recordingNoteId = null
            }
        }
    }
    
    /**
     * Dismiss error message.
     */
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    /**
     * Reset the ViewModel state to initial values.
     * Should be called when navigating to the recording screen.
     */
    fun resetState() {
        _uiState.update { 
            RecordingUiState(
                isRecording = false,
                elapsedTimeMs = 0,
                waveformAmplitudes = emptyList(),
                errorMessage = null,
                recordingSaved = false,
                isProcessing = false
            )
        }
    }
    
    /**
     * Start the timer that updates elapsed time.
     */
    private fun startTimer() {
        timerJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (_uiState.value.isRecording) {
                val elapsed = System.currentTimeMillis() - startTime
                _uiState.update { it.copy(elapsedTimeMs = elapsed) }
                delay(100) // Update every 100ms
            }
        }
    }
    
    /**
     * Start sampling audio amplitude for waveform visualization.
     */
    private fun startAmplitudeSampling() {
        amplitudeSamplingJob = viewModelScope.launch {
            // Wait a bit for MediaRecorder to stabilize
            delay(100)
            
            while (_uiState.value.isRecording) {
                val amplitude = audioRecorder.getMaxAmplitude()
                // Normalize to 0.0-1.0 range
                val normalized = (amplitude.toFloat() / AudioRecorder.MAX_AMPLITUDE)
                    .coerceIn(0f, 1f)
                
                _uiState.update { state ->
                    val newAmplitudes = state.waveformAmplitudes.toMutableList()
                    newAmplitudes.add(normalized)
                    
                    // Keep only last 100 samples (circular buffer)
                    if (newAmplitudes.size > MAX_WAVEFORM_SAMPLES) {
                        newAmplitudes.removeAt(0)
                    }
                    
                    state.copy(waveformAmplitudes = newAmplitudes)
                }
                
                delay(50) // Sample every 50ms for smooth animation
            }
        }
    }
    
    /**
     * Generate a recording title with timestamp.
     * Format: "Recording-YYYY-MM-DD-HH-mm"
     */
    private fun generateRecordingTitle(): String {
        val timestamp = formatDateForFilename()
        return "Recording-$timestamp"
    }
    
    /**
     * Clean up resources when ViewModel is destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        amplitudeSamplingJob?.cancel()
        audioRecorder.release()
    }
    
    companion object {
        private const val MAX_WAVEFORM_SAMPLES = 100
    }
}

/**
 * UI state for the recording screen.
 */
data class RecordingUiState(
    val isRecording: Boolean = false,
    val elapsedTimeMs: Long = 0,
    val waveformAmplitudes: List<Float> = emptyList(),
    val errorMessage: String? = null,
    val recordingSaved: Boolean = false,
    val isProcessing: Boolean = false  // Processing after stop (M4A→WAV, waveform, DB save)
)
