package demo.nexa.plauid.asr

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
        // Fast path: already initialized
        if (isInitialized && asrWrapper != null) {
            return@withContext
        }
        
        // Use mutex for coroutine-safe initialization
        initMutex.withLock {
            // Double-check after acquiring lock
            if (isInitialized && asrWrapper != null) {
                return@withContext
            }
            
            // Ensure model is installed from assets
            if (!modelManager.ensureModelInstalled()) {
                throw IllegalStateException("Failed to install ASR model from assets")
            }
            
            // Verify model availability
            if (!modelManager.isParakeetModelAvailable()) {
                throw IllegalStateException(modelManager.getModelStatusMessage())
            }
            
            val modelDir = modelManager.getParakeetModelDir()
            val modelFilePath = File(modelDir, "files-1-2.nexa").absolutePath
            val modelDirPath = modelDir.absolutePath
            
            // Build AsrWrapper using Nexa SDK
            // For NPU models: model_path = specific file, npu_model_folder_path = directory
            val result = AsrWrapper.builder()
                .asrCreateInput(
                    AsrCreateInput(
                        model_name = "parakeet",
                        model_path = modelFilePath,  // Point to specific model file
                        config = ModelConfig(
                            max_tokens = 2048,
                            npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
                            npu_model_folder_path = modelDirPath  // Point to model directory
                        ),
                        plugin_id = "npu"  // Use NPU backend
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
            // Ensure wrapper is ready
            ensureReady()
            
            val wrapper = asrWrapper ?: return@withContext Result.failure(
                IllegalStateException("ASR wrapper not initialized")
            )
            
            // Perform transcription using Nexa SDK's suspend API
            val transcriptionResult = wrapper.transcribe(
                AsrTranscribeInput(
                    audioPath = audioPath,
                    language = language,
                    config = null  // Use default config
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
