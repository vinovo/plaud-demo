package demo.nexa.plauid.llm

import android.content.Context
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Result types for SOAP summary generation.
 */
sealed class SoapGenerationResult {
    data class Token(val text: String) : SoapGenerationResult()
    data object Completed : SoapGenerationResult()
    data class Error(val throwable: Throwable) : SoapGenerationResult()
    
    // Progress events for 2-phase tracking
    data object SummarizerStarted : SoapGenerationResult()
    data class SummarizerCompleted(val totalSummaryLength: Int) : SoapGenerationResult()
    data object SoapCreatorStarted : SoapGenerationResult()
}

/**
 * Helper data class for model configuration.
 */
private data class ModelLoadConfig(
    val modelPath: String,
    val modelName: String,
    val pluginId: String,
    val config: ModelConfig,
    val deviceId: String?
)

/**
 * Wrapper for Nexa SDK LLM functionality.
 * Handles LlmWrapper lifecycle and provides a clean suspend API for text generation.
 * Uses different plugins for two models:
 * - Liquid (LFM2.5-1.2B GGUF) for section summarization
 * - Qwen (Qwen3-4B GGUF) for SOAP note creation
 */
class NexaLlmEngine private constructor(
    private val context: Context,
    private val modelManager: LlmModelManager
) {
    
    @Volatile
    internal var currentWrapper: LlmWrapper? = null
    
    @Volatile
    private var currentModelType: LlmModelManager.ModelType? = null
    
    private val modelMutex = Mutex()
    
    /**
     * Load a specific model and make it ready for use.
     * Destroys any currently loaded model before loading the new one.
     * 
     * @param modelType Which model to load (Liquid or Qwen)
     * @throws IllegalStateException if model is not available
     * @throws Exception if LLM wrapper creation fails
     */
    suspend fun loadModel(modelType: LlmModelManager.ModelType) = withContext(Dispatchers.IO) {
        modelMutex.withLock {
            // If the requested model is already loaded, nothing to do
            if (currentModelType == modelType && currentWrapper != null) {
                Log.d(TAG, "${modelType.name} model already loaded")
                return@withLock
            }
            
            // Destroy current model if loaded
            if (currentWrapper != null) {
                Log.d(TAG, "Destroying ${currentModelType?.name} model before loading ${modelType.name}")
                currentWrapper?.destroy()
                currentWrapper = null
                currentModelType = null
            }
            
            // Ensure model is installed from assets
            val modelInstalled = modelManager.ensureModelInstalled(modelType)
            
            // Verify model availability
            if (!modelManager.isModelAvailable(modelType)) {
                val message = if (modelInstalled) {
                    "${modelType.name} model installation completed but validation failed"
                } else {
                    "${modelType.name} model not available. Please add model to assets."
                }
                throw IllegalStateException(message)
            }
            
            val modelConfig = when (modelType) {
                LlmModelManager.ModelType.LIQUID_SUMMARIZER -> {
                    // GGUF model configuration
                    val path = modelManager.getLiquidModelPath().absolutePath
                    val name = ""  // GGUF: keep model_name empty
                    val plugin = "cpu_gpu"
                    val cfg = ModelConfig(
                        nCtx = 8192,
                        nGpuLayers = 999
                    )
                    ModelLoadConfig(path, name, plugin, cfg, "dev0")
                }
                LlmModelManager.ModelType.QWEN_SOAP_CREATOR -> {
                    // GGUF model configuration
                    val path = modelManager.getQwenModelPath().absolutePath
                    val name = ""  // GGUF: keep model_name empty
                    val plugin = "cpu_gpu"
                    val cfg = ModelConfig(
                        nCtx = 8192,
                        nGpuLayers = 999
                    )
                    ModelLoadConfig(path, name, plugin, cfg, "dev0")
                }
            }
            
            Log.d(TAG, "Loading ${modelType.name} model")
            
            // Build LlmWrapper using Nexa SDK
            val result = LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_name = modelConfig.modelName,
                        model_path = modelConfig.modelPath,
                        config = modelConfig.config,
                        plugin_id = modelConfig.pluginId,
                        device_id = modelConfig.deviceId
                    )
                )
                .build()
            
            result.onSuccess { wrapper ->
                currentWrapper = wrapper
                currentModelType = modelType
                Log.d(TAG, "${modelType.name} model loaded successfully")
            }.onFailure { error ->
                Log.e(TAG, "Failed to load ${modelType.name} model", error)
                throw Exception("Failed to load ${modelType.name}: ${error.message}", error)
            }
        }
    }
    
    /**
     * Generate a SOAP note summary from a therapy session transcript.
     * Uses a two-stage process for long transcripts:
     * 1. Section summarizer (for transcripts >= 2000 chars): chunks into 2000-char segments and summarizes each
     *    - Each segment takes ~1 minute expected completion time
     *    - This stage accounts for the first 50% of progress
     * 2. SOAP creator: generates the final SOAP note from the summaries (or original transcript if short)
     *    - Takes ~2 minutes expected completion time
     *    - Accounts for the last 50% of progress (or 100% if no section summarization)
     * 
     * @param transcript The full therapy session transcript text
     * @return Flow emitting progress updates, text chunks, and completion/error events
     * @throws IllegalStateException if wrapper is not initialized
     * @throws Exception on generation errors
     */
    fun generateSoapSummary(transcript: String): Flow<SoapGenerationResult> = channelFlow {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting SOAP summary generation (transcript length: ${transcript.length})")
            
            // Determine input for SOAP creator
            val soapInput = if (transcript.length < SEGMENT_SIZE) {
                transcript
            } else {
                Log.d(TAG, "Running section summarization")
                
                // Emit: Phase 1 started
                send(SoapGenerationResult.SummarizerStarted)
                
                // Load Liquid model for section summarization
                loadModel(LlmModelManager.ModelType.LIQUID_SUMMARIZER)
                
                // Generate summaries
                val segments = chunkTranscript(transcript, SEGMENT_SIZE)
                val summaries = generateSectionSummaries(segments)
                
                val joinedSummaries = summaries.joinToString("\n\n")
                Log.d(TAG, "Segment summaries completed (${joinedSummaries.length} chars)")
                
                // Emit: Phase 1 completed with actual summary length
                send(SoapGenerationResult.SummarizerCompleted(joinedSummaries.length))
                
                joinedSummaries
            }
            
            // Emit: Phase 2 starting
            send(SoapGenerationResult.SoapCreatorStarted)
            
            // Load Qwen GGUF model for SOAP creation (this will automatically destroy Liquid if loaded)
            Log.d(TAG, "Loading Qwen model for SOAP creation")
            loadModel(LlmModelManager.ModelType.QWEN_SOAP_CREATOR)
            
            val wrapper = currentWrapper ?: throw IllegalStateException("Failed to load Qwen model")
            
            // Build chat message with SOAP system prompt and input
            val chatMessages = arrayListOf(
                ChatMessage("system", SOAP_SYSTEM_PROMPT),
                ChatMessage("user", SOAP_USER_PREFIX + soapInput)
            )
            
            // Apply chat template
            val templateResult = wrapper.applyChatTemplate(chatMessages.toTypedArray(), null, false)
            
            templateResult.onSuccess { template ->
                Log.d(TAG, "Chat template applied successfully for SOAP creation")
                
                val genConfig = GenerationConfig(maxTokens = 4096)
                
                // Generate with streaming
                wrapper.generateStreamFlow(template.formattedText, genConfig).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> {
                            send(SoapGenerationResult.Token(result.text))
                        }
                        is LlmStreamResult.Completed -> {
                            Log.d(TAG, "SOAP summary generation completed")
                            
                            // Reset LLM state after completion for cleanup
                            wrapper.reset()
                            
                            send(SoapGenerationResult.Completed)
                        }
                        is LlmStreamResult.Error -> {
                            Log.e(TAG, "SOAP summary generation error", result.throwable)
                            send(SoapGenerationResult.Error(result.throwable))
                        }
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to apply chat template", error)
                send(SoapGenerationResult.Error(error))
            }
        }
    }
    
    /**
     * Generate summaries for each segment.
     * 
     * @param segments List of transcript segments to summarize
     * @return List of summaries, one per segment
     * @throws Exception on generation errors
     */
    private suspend fun generateSectionSummaries(
        segments: List<String>
    ): List<String> = withContext(Dispatchers.IO) {
        val wrapper = currentWrapper ?: throw IllegalStateException("Liquid model not loaded")
        val summaries = mutableListOf<String>()
        
        Log.d(TAG, "Summarizing ${segments.size} segments")
        
        segments.forEachIndexed { index, segment ->
            Log.d(TAG, "Summarizing segment ${index + 1}/${segments.size}")
            
            // Build chat message with section summarizer prompt
            val chatMessages = arrayListOf(
                ChatMessage("system", SECTION_SUMMARIZER_PROMPT),
                ChatMessage("user", SUMMARIZER_USER_PREFIX + segment)
            )
            
            // Apply chat template
            val templateResult = wrapper.applyChatTemplate(chatMessages.toTypedArray(), null, false)
            
            templateResult.onSuccess { template ->
                // Generate summary (blocking, non-streaming)
                val summaryBuilder = StringBuilder()
                var hasError = false
                var errorThrowable: Throwable? = null
                
                val genConfig = GenerationConfig(maxTokens = 4096)
                
                wrapper.generateStreamFlow(template.formattedText, genConfig).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> {
                            summaryBuilder.append(result.text)
                        }
                        is LlmStreamResult.Completed -> {
                            Log.d(TAG, "Segment ${index + 1} summarization completed")
                        }
                        is LlmStreamResult.Error -> {
                            hasError = true
                            errorThrowable = result.throwable
                            Log.e(TAG, "Segment ${index + 1} summarization error", result.throwable)
                        }
                    }
                }
                
                if (hasError) {
                    throw errorThrowable ?: Exception("Unknown error during segment summarization")
                }
                
                val segmentSummary = summaryBuilder.toString()
                summaries.add(segmentSummary)
                
                // Reset LLM state after each segment to clear KV cache
                wrapper.reset()
            }.onFailure { error ->
                Log.e(TAG, "Failed to apply chat template for segment ${index + 1}", error)
                throw error
            }
        }
        
        return@withContext summaries
    }
    
    /**
     * Chunk a transcript into segments of specified size.
     * Attempts to split at natural sentence boundaries (periods) for better context preservation.
     * Falls back to hard split at segmentSize if:
     * 1. No period exists in the segment
     * 2. The last period is in the first half of the segment
     * 
     * @param transcript The full transcript text
     * @param segmentSize The maximum size of each segment in characters
     * @return List of transcript segments
     */
    private fun chunkTranscript(transcript: String, segmentSize: Int): List<String> {
        if (transcript.length <= segmentSize) {
            return listOf(transcript)
        }
        
        val segments = mutableListOf<String>()
        var currentIndex = 0
        
        while (currentIndex < transcript.length) {
            val remainingLength = transcript.length - currentIndex
            
            if (remainingLength <= segmentSize) {
                // Last segment - add everything remaining
                val lastSegment = transcript.substring(currentIndex)
                segments.add(lastSegment)
                break
            }
            
            // Try to find a natural split point at a sentence boundary
            val chunkEnd = currentIndex + segmentSize
            val chunk = transcript.substring(currentIndex, chunkEnd)
            
            // Find the last period in this chunk
            val lastPeriodIndex = chunk.lastIndexOf('.')
            
            // Determine the split point
            val splitPoint = if (lastPeriodIndex != -1 && lastPeriodIndex >= segmentSize / 2) {
                // Found a period in the second half - split after it (include the period)
                currentIndex + lastPeriodIndex + 1
            } else {
                // No period found, or period is too early - use hard split
                chunkEnd
            }
            
            val segment = transcript.substring(currentIndex, splitPoint)
            segments.add(segment)
            currentIndex = splitPoint
        }
        
        return segments
    }
    
    /**
     * Generate a SOAP note summary (non-streaming version).
     * Collects all tokens and returns the complete summary.
     * 
     * @param transcript The full therapy session transcript text
     * @return Result containing the complete SOAP note or error
     */
    suspend fun generateSoapSummaryBlocking(
        transcript: String
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val summaryBuilder = StringBuilder()
            var hasError = false
            var errorThrowable: Throwable? = null
            
            generateSoapSummary(transcript).collect { result ->
                when (result) {
                    is SoapGenerationResult.Token -> {
                        summaryBuilder.append(result.text)
                    }
                    is SoapGenerationResult.Completed -> {
                        Log.d(TAG, "SOAP generation completed")
                    }
                    is SoapGenerationResult.Error -> {
                        hasError = true
                        errorThrowable = result.throwable
                    }
                    // Ignore progress events in blocking version
                    is SoapGenerationResult.SummarizerStarted,
                    is SoapGenerationResult.SummarizerCompleted,
                    is SoapGenerationResult.SoapCreatorStarted -> {
                        // No-op in blocking mode
                    }
                }
            }
            
            if (hasError) {
                Result.failure(errorThrowable ?: Exception("Unknown error during SOAP generation"))
            } else {
                Result.success(summaryBuilder.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during blocking SOAP generation", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if the LLM engine has a model loaded and ready to use.
     */
    fun isReady(): Boolean = currentWrapper != null
    
    /**
     * Get model status information.
     */
    fun getModelStatus(): String = modelManager.getModelStatusMessage()
    
    /**
     * Release resources. Call when the engine is no longer needed.
     */
    fun release() {
        currentWrapper?.destroy()
        currentWrapper = null
        currentModelType = null
        Log.d(TAG, "LLM engine released")
    }
    
    companion object {
        private const val TAG = "NexaLlmEngine"
        
        /**
         * Size of each transcript segment for section summarization (in characters).
         */
        const val SEGMENT_SIZE = 2000
        
        /**
         * LLM inference speeds (measured from benchmarks).
         * LFM2.5-1.2B (Summarizer): 5 ms/char
         * Qwen3-4B (SOAP Creator): 27.5 ms/char
         */
        const val SUMMARIZER_MS_PER_CHAR = 5.0
        const val SOAP_CREATOR_MS_PER_CHAR = 27.5
        
        /**
         * User message prefix for section summarization.
         */
        const val SUMMARIZER_USER_PREFIX = "Summarize the following transcript segment:\n\n"
        
        /**
         * User message prefix for SOAP note generation.
         */
        const val SOAP_USER_PREFIX = "Generate a SOAP note from the following therapy session transcript:\n\n"
        
        @Volatile
        private var INSTANCE: NexaLlmEngine? = null
        
        fun getInstance(context: Context): NexaLlmEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NexaLlmEngine(
                    context.applicationContext,
                    LlmModelManager.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
        
        /**
         * System prompt for section summarization.
         * Summarizes individual segments of a long transcript while preserving key information.
         */
        const val SECTION_SUMMARIZER_PROMPT = """You are summarizing ONE SEGMENT of a therapy session transcript. This segment will be used to create a SOAP note (Subjective, Objective, Assessment, Plan).

Preserve information relevant to SOAP documentation:
* **Subjective**: Patient-reported symptoms, concerns, emotions, statements, experiences
* **Objective**: Therapist observations (affect, behavior, engagement, appearance)
* **Assessment**: Clinical impressions, progress, working hypotheses explicitly stated
* **Plan**: Interventions provided, next steps, homework, referrals, treatment goals

Also preserve:
* Direct quotes when clinically significant
* Specific terms (medications, diagnoses, clinical terminology)
* Time, location, or context if mentioned

**Important**: Not all information will be present in every segment. Only include what is actually discussed. It's OK if a segment has limited content.

Be faithful to the source, maintain clinical neutrality, and use concise professional language. Return only the summary."""
        
        /**
         * System prompt for SOAP note generation.
         * Transforms therapist session transcripts into structured clinical documentation.
         */
        const val SOAP_SYSTEM_PROMPT = """You are a clinical documentation assistant. Your sole task is to transform therapist–patient communication into a structured SOAP note (Subjective, Objective, Assessment, Plan).
You must summarize accurately, neutrally, and conservatively, without adding information that is not explicitly supported by the source text.

### Output Format (Strict)

Return only a SOAP note using the following structure and headers:

**Provider:** Licensed Clinical Social Worker
**Client:** Jane D., DOB 03/12/1995
**Date of Service:** 2024-10-18

**S — Subjective**

* Patient-reported symptoms, concerns, emotions, stressors, goals
* Direct paraphrases of patient statements
* Include context (timeframe, triggers, severity) when stated
* Do not infer motivation, diagnosis, or intent

**O — Objective**

* Therapist observations (affect, behavior, engagement, speech, appearance)
* Observable facts only (attendance, responsiveness, participation)
* No interpretation beyond what is directly observable

**A — Assessment**

* Therapist's clinical impressions explicitly supported by the input
* Progress, stability, or change relative to prior sessions if stated
* Working hypotheses may be included only if the therapist explicitly indicates them
* Do not introduce new diagnoses or clinical judgments

**P — Plan**

* Interventions provided during the session
* Agreed-upon next steps, homework, follow-ups, referrals
* Treatment goals or adjustments only if mentioned
* Include timeframe or frequency when specified

### Rules & Constraints (Critical)

* Do not fabricate details, symptoms, diagnoses, or plans
* Do not use diagnostic labels unless explicitly stated
* Do not normalize, judge, or provide reassurance language
* Do not include meta commentary (e.g., "the patient seems…")
* If information for a section is missing, write: "Not explicitly discussed in session."

### Tone & Style

* Clinical, neutral, concise
* Third-person, professional documentation language
* Past tense
* No emojis, no conversational phrasing

Return only the SOAP note. No explanations, no preamble, no post-notes."""
    }
}
