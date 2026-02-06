package demo.nexa.clinical_transcription_demo.asr

import android.content.Context
import android.util.Log
import com.nexa.sdk.AsrWrapper
import com.nexa.sdk.bean.AsrCreateInput
import com.nexa.sdk.bean.AsrTranscribeInput
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wrapper for Nexa SDK ASR functionality.
 * Handles AsrWrapper lifecycle and provides a clean suspend API for transcription.
 */
class NexaAsrEngine private constructor(
    private val context: Context,
    private val modelManager: AsrModelManager
) {
    
    @Volatile
    private var asrWrapper: AsrWrapper? = null
    
    @Volatile
    private var isInitialized = false
    
    private val initMutex = Mutex()
    
    /**
     * Ensure the ASR wrapper is loaded and ready.
     * This is idempotent - safe to call multiple times.
     * 
     * @throws IllegalStateException if model is not available
     * @throws Exception if ASR wrapper creation fails
     */
    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        if (isInitialized && asrWrapper != null) {
            return@withContext
        }
        
        initMutex.withLock {
            if (isInitialized && asrWrapper != null) {
                return@withContext
            }
            
            if (!modelManager.ensureModelInstalled()) {
                throw IllegalStateException("Failed to install ASR model from assets")
            }
            
            if (!modelManager.isParakeetModelAvailable()) {
                throw IllegalStateException(modelManager.getModelStatusMessage())
            }
            
            val modelDir = modelManager.getParakeetModelDir()
            val modelFilePath = File(modelDir, "files-1-2.nexa").absolutePath
            val modelDirPath = modelDir.absolutePath
            
            val result = AsrWrapper.builder()
                .asrCreateInput(
                    AsrCreateInput(
                        model_name = "parakeet",
                        model_path = modelFilePath,
                        config = ModelConfig(
                            max_tokens = 2048,
                            npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
                            npu_model_folder_path = modelDirPath
                        ),
                        plugin_id = "npu"
                    )
                )
                .build()
            
            result.onSuccess { wrapper ->
                asrWrapper = wrapper
                isInitialized = true
            }.onFailure { error ->
                Log.e(TAG, "Failed to initialize ASR wrapper", error)
                throw Exception("Failed to initialize ASR: ${error.message}", error)
            }
        }
    }
    
    /**
     * Transcribe an audio file to text.
     * Automatically ensures the ASR engine is ready before transcription.
     * 
     * @param audioPath Absolute path to the audio file (.wav, .mp3, etc.)
     * @param language Language code (e.g., "en", "zh", "es")
     * @return Result containing the transcript text or error
     */
    suspend fun transcribe(
        audioPath: String,
        language: String = "en"
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            ensureReady()
            
            val wrapper = asrWrapper ?: return@withContext Result.failure(
                IllegalStateException("ASR wrapper not initialized")
            )
            
            val transcriptionResult = wrapper.transcribe(
                AsrTranscribeInput(
                    audioPath = audioPath,
                    language = language,
                    config = null
                )
            )
            
            transcriptionResult.onFailure { error ->
                Log.e(TAG, "Transcription failed", error)
            }
            
            transcriptionResult.map { it.result.transcript ?: "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error during transcription", e)
            Result.failure(e)
        }
    }
    
    /**
     * Release resources. Call when the engine is no longer needed.
     */
    fun release() {
        asrWrapper = null
        isInitialized = false
    }
    
    companion object {
        private const val TAG = "NexaAsrEngine"
        
        @Volatile
        private var INSTANCE: NexaAsrEngine? = null
        
        fun getInstance(context: Context): NexaAsrEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NexaAsrEngine(
                    context.applicationContext,
                    AsrModelManager.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }
}
