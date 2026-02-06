package demo.nexa.clinical_transcription_demo.asr

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Manages ASR model storage and path resolution.
 * Models are bundled in assets and copied to app storage on first run.
 */
class AsrModelManager(private val context: Context) {
    
    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * Get the model directory for the Parakeet ASR model.
     * Expected structure: filesDir/nexa_models/parakeet-tdt-0.6b-v3-npu-mobile/
     * 
     * @return File pointing to the model directory
     */
    fun getParakeetModelDir(): File {
        return File(modelsDir, PARAKEET_MODEL_NAME)
    }
    
    /**
     * Check if the Parakeet model exists and is ready to use.
     * 
     * @return true if model directory exists and is not empty
     */
    fun isParakeetModelAvailable(): Boolean {
        val modelDir = getParakeetModelDir()
        return modelDir.exists() && modelDir.isDirectory && (modelDir.listFiles()?.isNotEmpty() == true)
    }
    
    /**
     * Ensure the ASR model is installed from assets to filesDir.
     * Copies from assets if target folder doesn't exist.
     * 
     * @return true if model is available, false if installation failed
     */
    fun ensureModelInstalled(): Boolean {
        val modelDir = getParakeetModelDir()
        
        if (isParakeetModelAvailable()) {
            return true
        }
        
        try {
            val assetFiles = context.assets.list(ASSETS_MODEL_PATH)
            if (assetFiles.isNullOrEmpty()) {
                Log.e(TAG, "Model not found in assets at: $ASSETS_MODEL_PATH")
                return false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to check assets for model", e)
            return false
        }
        
        return try {
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            
            copyModelFromAssets(ASSETS_MODEL_PATH, modelDir)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install model from assets", e)
            
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            
            false
        }
    }
    
    /**
     * Recursively copy model files from assets to filesDir.
     * Assumes fixed model folder structure - no complex detection needed.
     */
    private fun copyModelFromAssets(assetPath: String, destFile: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath) ?: emptyArray()
        
        if (files.isNotEmpty()) {
            destFile.mkdirs()
            files.forEach { filename ->
                copyModelFromAssets("$assetPath/$filename", File(destFile, filename))
            }
        } else {
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
        return if (isParakeetModelAvailable()) {
            "ASR model ready"
        } else {
            "ASR model not found. Please ensure the app includes model assets."
        }
    }
    
    companion object {
        private const val TAG = "AsrModelManager"
        private const val MODELS_FOLDER_NAME = "nexa_models"
        private const val PARAKEET_MODEL_NAME = "parakeet-tdt-0.6b-v3-npu-mobile"
        private const val ASSETS_MODEL_PATH = "nexa_models/$PARAKEET_MODEL_NAME"
        
        @Volatile
        private var INSTANCE: AsrModelManager? = null
        
        fun getInstance(context: Context): AsrModelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AsrModelManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
