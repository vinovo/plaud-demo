package demo.nexa.clinical_transcription_demo.audio

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
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setAudioChannels(1)
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
    
    fun isRecording(): Boolean = isRecording
    
    fun release() {
        try {
            mediaRecorder?.apply {
                if (isRecording) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }
    
    companion object {
        const val MAX_AMPLITUDE = 32767
    }
}
