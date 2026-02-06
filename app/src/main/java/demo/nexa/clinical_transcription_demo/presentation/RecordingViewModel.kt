package demo.nexa.clinical_transcription_demo.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import demo.nexa.clinical_transcription_demo.audio.AudioRecorder
import demo.nexa.clinical_transcription_demo.audio.AudioTranscoder
import demo.nexa.clinical_transcription_demo.common.formatDateForFilename
import demo.nexa.clinical_transcription_demo.data.audio.AudioFileManager
import demo.nexa.clinical_transcription_demo.data.repository.NotesRepository
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
    private var recordingNoteId: String? = null
    
    fun startRecording() {
        if (_uiState.value.isRecording) return
        
        viewModelScope.launch {
            try {
                val noteId = UUID.randomUUID().toString()
                recordingNoteId = noteId
                
                val outputFile = audioFileManager.createRecordingFile(noteId, "m4a")
                
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
    
    fun discardRecording() {
        if (!_uiState.value.isRecording) return
        
        val noteId = recordingNoteId
        
        timerJob?.cancel()
        amplitudeSamplingJob?.cancel()
        
        viewModelScope.launch {
            try {
                val result = audioRecorder.stopRecording()
                
                result.onSuccess { m4aFile ->
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
        
        timerJob?.cancel()
        amplitudeSamplingJob?.cancel()
        
        _uiState.update { it.copy(isProcessing = true) }
        
        viewModelScope.launch {
            try {
                val result = audioRecorder.stopRecording()
                
                result.onSuccess { m4aFile ->
                    withContext(Dispatchers.IO) {
                        val wavFile = audioFileManager.createWavFile(noteId)
                        
                        val conversionResult = audioTranscoder.convertToWav(
                            sourceFile = m4aFile,
                            outputFile = wavFile,
                            deleteSource = true
                        )
                        
                        conversionResult.onSuccess { audioInfo ->
                            val expectedSampleRate = 16000
                            if (audioInfo.sampleRate != expectedSampleRate) {
                                android.util.Log.w(
                                    "RecordingViewModel",
                                    "WAV sample rate is ${audioInfo.sampleRate}Hz, expected ${expectedSampleRate}Hz"
                                )
                            }
                            
                            val title = generateRecordingTitle()
                            val duration = _uiState.value.elapsedTimeMs
                            
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
    
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
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
    
    private fun startAmplitudeSampling() {
        amplitudeSamplingJob = viewModelScope.launch {
            delay(100)
            
            while (_uiState.value.isRecording) {
                val amplitude = audioRecorder.getMaxAmplitude()
                val normalized = (amplitude.toFloat() / AudioRecorder.MAX_AMPLITUDE)
                    .coerceIn(0f, 1f)
                
                _uiState.update { state ->
                    val newAmplitudes = state.waveformAmplitudes.toMutableList()
                    newAmplitudes.add(normalized)
                    
                    if (newAmplitudes.size > MAX_WAVEFORM_SAMPLES) {
                        newAmplitudes.removeAt(0)
                    }
                    
                    state.copy(waveformAmplitudes = newAmplitudes)
                }
                
                delay(50)
            }
        }
    }
    
    private fun generateRecordingTitle(): String {
        val timestamp = formatDateForFilename()
        return "Recording-$timestamp"
    }
    
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
