package demo.nexa.plauid.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Wrapper around MediaRecorder for audio recording.
 * Handles recording configuration, lifecycle, and amplitude sampling for waveform.
 */
class AudioRecorder(private val context: Context) {
    
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    
    /**
     * Start recording to the specified output file.
     *
     * @param outputFile File to write audio to
     * @return Result indicating success or failure
     */
    fun startRecording(outputFile: File): Result<Unit> {
        return try {
            this.outputFile = outputFile
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4) // M4A container
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)    // AAC codec
                setAudioSamplingRate(16000)  // 16 kHz for speech
                setAudioEncodingBitRate(64000) // 64 kbps
                setAudioChannels(1)  // Mono
                setOutputFile(outputFile.absolutePath)
                
                prepare()
                start()
            }
            
            isRecording = true
            Result.success(Unit)
            
        } catch (e: Exception) {
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            Result.failure(e)
        }
    }
    
    /**
     * Stop recording and finalize the output file.
     *
     * @return Result containing the output file or failure
     */
    fun stopRecording(): Result<File> {
        return try {
            if (!isRecording || mediaRecorder == null) {
                return Result.failure(IllegalStateException("Not currently recording"))
            }
            
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            
            val file = outputFile ?: return Result.failure(
                IllegalStateException("Output file not set")
            )
            
            if (!file.exists() || file.length() == 0L) {
                return Result.failure(
                    IllegalStateException("Recording file is empty or missing")
                )
            }
            
            Result.success(file)
            
        } catch (e: Exception) {
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            Result.failure(e)
        }
    }
    
    /**
     * Get the current maximum amplitude for waveform visualization.
     * Returns 0 if not recording.
     *
     * @return Amplitude value (0-32767 for MediaRecorder)
     */
    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder?.maxAmplitude ?: 0
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Check if currently recording.
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Release resources. Should be called when done with the recorder.
     */
    fun release() {
        try {
            mediaRecorder?.apply {
                if (isRecording) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            // Ignore errors during cleanup
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }
    
    companion object {
        /**
         * Maximum amplitude value returned by MediaRecorder.getMaxAmplitude()
         */
        const val MAX_AMPLITUDE = 32767
    }
}
