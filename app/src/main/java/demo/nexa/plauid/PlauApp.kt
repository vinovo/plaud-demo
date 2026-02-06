package demo.nexa.plauid

import android.app.Application
import android.util.Log
import com.nexa.sdk.NexaSdk

/**
 * Application subclass for one-time initialization.
 * Initializes Nexa SDK runtime (shared by ASR and LLM modules).
 */
class PlauApp : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Nexa SDK once for the entire app lifecycle
        NexaSdk.getInstance().init(this, object : NexaSdk.InitCallback {
            override fun onSuccess() {
                Log.d(TAG, "Nexa SDK initialized successfully")
            }
            
            override fun onFailure(reason: String) {
                Log.e(TAG, "Failed to initialize Nexa SDK: $reason")
            }
        })
    }
    
    companion object {
        private const val TAG = "PlauApp"
    }
}
