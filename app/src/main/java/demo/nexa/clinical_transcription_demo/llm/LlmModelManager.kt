package demo.nexa.clinical_transcription_demo.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Manages LLM model storage and path resolution.
 * Models are bundled in assets and copied to app storage on first run.
 * Supports multiple models: Liquid (summarizer) and Qwen (SOAP creator).
 */
class LlmModelManager(private val context: Context) {
    
    enum class ModelType {
        LIQUID_SUMMARIZER,
        QWEN_SOAP_CREATOR
    }
    
    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * Get the path to the Liquid model file (GGUF format).
     * Expected structure: filesDir/nexa_models/LFM2.5-1.2B-Instruct-GGUF/LFM2.5-1.2B-Instruct-Q4_K_M.gguf
     * 
     * @return File pointing to the model file
     */
    fun getLiquidModelPath(): File {
        return File(File(modelsDir, LIQUID_MODEL_FOLDER), LIQUID_MODEL_FILENAME)
    }
    
    /**
     * Get the path to the Qwen model file (GGUF format).
     * Expected structure: filesDir/nexa_models/Qwen3-4B-GGUF/Qwen3-4B-Q4_K_M.gguf
     * 
     * @return File pointing to the model file
     */
    fun getQwenModelPath(): File {
        return File(File(modelsDir, QWEN_MODEL_FOLDER), QWEN_MODEL_FILENAME)
    }
    
    /**
     * Check if a model is available and ready to use.
     * 
     * @param modelType The type of model to check
     * @return true if the model is available (file exists for GGUF models)
     */
    fun isModelAvailable(modelType: ModelType): Boolean {
        return when (modelType) {
            ModelType.LIQUID_SUMMARIZER -> {
                // GGUF model: check file exists
                val modelPath = getLiquidModelPath()
                modelPath.exists() && modelPath.isFile && modelPath.length() > 0
            }
            ModelType.QWEN_SOAP_CREATOR -> {
                // GGUF model: check file exists
                val modelPath = getQwenModelPath()
                modelPath.exists() && modelPath.isFile && modelPath.length() > 0
            }
        }
    }
    
    /**
     * Ensure a model is installed from assets to filesDir.
     * Copies from assets if target folder doesn't exist or is empty.
     * 
     * @param modelType The type of model to install
     * @return true if model is available, false if installation failed
     */
    fun ensureModelInstalled(modelType: ModelType): Boolean {
        if (isModelAvailable(modelType)) {
            return true
        }
        
        val (modelFolder, assetsPath) = when (modelType) {
            ModelType.LIQUID_SUMMARIZER -> LIQUID_MODEL_FOLDER to ASSETS_LIQUID_PATH
            ModelType.QWEN_SOAP_CREATOR -> QWEN_MODEL_FOLDER to ASSETS_QWEN_PATH
        }
        
        val modelDir = File(modelsDir, modelFolder)
        
        try {
            val assetFiles = context.assets.list(assetsPath)
            if (assetFiles.isNullOrEmpty()) {
                Log.w(TAG, "No ${modelType.name} model found in assets")
                return false
            }
        } catch (e: IOException) {
            Log.w(TAG, "Assets folder not found: $assetsPath", e)
            return false
        }
        
        return try {
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            
            modelDir.mkdirs()
            copyModelFromAssets(assetsPath, modelDir)
            
            val installed = isModelAvailable(modelType)
            if (!installed) {
                Log.w(TAG, "${modelType.name} model installation failed validation")
            }
            installed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install ${modelType.name} model from assets", e)
            
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            
            false
        }
    }
    
    /**
     * Recursively copy model files from assets to filesDir.
     */
    private fun copyModelFromAssets(assetPath: String, destFile: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath) ?: emptyArray()
        
        if (files.isNotEmpty()) {
            // Directory - recurse into children
            destFile.mkdirs()
            files.forEach { filename ->
                copyModelFromAssets("$assetPath/$filename", File(destFile, filename))
            }
        } else {
            // File - copy it
            destFile.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
    
    /**
     * Get a human-readable status message about model availability.
     */
    fun getModelStatusMessage(): String {
        val liquidAvailable = isModelAvailable(ModelType.LIQUID_SUMMARIZER)
        val qwenAvailable = isModelAvailable(ModelType.QWEN_SOAP_CREATOR)
        
        return when {
            liquidAvailable && qwenAvailable -> "All models ready (Liquid + Qwen)"
            liquidAvailable -> "Liquid model ready, Qwen missing"
            qwenAvailable -> "Qwen model ready, Liquid missing"
            else -> "No models found. Please add models to assets/nexa_models/"
        }
    }
    
    companion object {
        private const val TAG = "LlmModelManager"
        private const val MODELS_FOLDER_NAME = "nexa_models"
        
        // Liquid model (summarizer) - GGUF format
        private const val LIQUID_MODEL_FOLDER = "LFM2.5-1.2B-Instruct-GGUF"
        private const val LIQUID_MODEL_FILENAME = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf"
        private const val ASSETS_LIQUID_PATH = "nexa_models/$LIQUID_MODEL_FOLDER"
        
        // Qwen model (SOAP creator) - GGUF format
        private const val QWEN_MODEL_FOLDER = "Qwen3-4B-GGUF"
        private const val QWEN_MODEL_FILENAME = "Qwen3-4B-Q4_K_M.gguf"
        private const val ASSETS_QWEN_PATH = "nexa_models/$QWEN_MODEL_FOLDER"
        
        @Volatile
        private var INSTANCE: LlmModelManager? = null
        
        fun getInstance(context: Context): LlmModelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlmModelManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
