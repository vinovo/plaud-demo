package demo.nexa.clinical_transcription_demo

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import demo.nexa.clinical_transcription_demo.llm.LlmModelManager
import demo.nexa.clinical_transcription_demo.llm.NexaLlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Test activity for LLM benchmarking.
 */
class TestLlmActivity : AppCompatActivity() {
    
    private lateinit var outputText: TextView
    private lateinit var startButton: Button
    private lateinit var scrollView: ScrollView
    
    private val testLengths = listOf(1000, 2000, 4000)
    private val runsPerLength = 3
    
    private val summarizerPrompt = """You are summarizing ONE SEGMENT of a therapy session transcript. This segment will be used to create a SOAP note (Subjective, Objective, Assessment, Plan).

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

    private val soapPrompt = """You are a clinical documentation assistant. Your sole task is to transform therapist–patient communication into a structured SOAP note (Subjective, Objective, Assessment, Plan).
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        startButton = Button(this).apply {
            text = "Start LLM Benchmark"
            setOnClickListener { runBenchmark() }
        }
        
        outputText = TextView(this).apply {
            text = "Click 'Start' to begin LLM benchmarking...\n\n"
            textSize = 12f
            setTextIsSelectable(true)
        }
        
        scrollView = ScrollView(this).apply {
            addView(outputText)
        }
        
        layout.addView(startButton)
        layout.addView(scrollView)
        
        setContentView(layout)
    }
    
    private fun runBenchmark() {
        startButton.isEnabled = false
        outputText.text = "Starting benchmark...\n\n"
        
        lifecycleScope.launch {
            try {
                // Load transcript from assets
                val transcript = loadTranscript()
                if (transcript.isEmpty()) {
                    appendOutput("ERROR: Could not load transcript.txt from assets\n")
                    startButton.isEnabled = true
                    return@launch
                }
                
                appendOutput("=== LLM BENCHMARK TEST ===\n")
                appendOutput("Transcript length: ${transcript.length} chars\n")
                appendOutput("Test lengths: ${testLengths.joinToString(", ")}\n")
                appendOutput("Runs per length: $runsPerLength\n")
                appendOutput("Total runs per task: ${testLengths.size * runsPerLength}\n\n")
                
                val llmEngine = NexaLlmEngine.getInstance(applicationContext)
                
                // Task 1: Summarizer
                appendOutput("=== TASK 1: SUMMARIZER ===\n\n")
                val summarizerResults = benchmarkTask(
                    llmEngine = llmEngine,
                    transcript = transcript,
                    modelType = LlmModelManager.ModelType.LIQUID_SUMMARIZER,
                    systemPrompt = summarizerPrompt,
                    taskName = "Summarizer"
                )
                
                // Task 2: SOAP Creator
                appendOutput("\n=== TASK 2: SOAP CREATOR ===\n\n")
                val soapResults = benchmarkTask(
                    llmEngine = llmEngine,
                    transcript = transcript,
                    modelType = LlmModelManager.ModelType.QWEN_SOAP_CREATOR,
                    systemPrompt = soapPrompt,
                    taskName = "SOAP Creator"
                )
                
                // Summary
                appendOutput("\n=== BENCHMARK SUMMARY ===\n")
                appendOutput("Summarizer avg time/char: %.4f ms/char\n".format(summarizerResults))
                appendOutput("SOAP Creator avg time/char: %.4f ms/char\n".format(soapResults))
                
                appendOutput("\nBenchmark complete!\n")
                
            } catch (e: Exception) {
                appendOutput("ERROR: ${e.message}\n")
                e.printStackTrace()
            } finally {
                startButton.isEnabled = true
            }
        }
    }
    
    /**
     * Benchmark a single task (Summarizer or SOAP Creator)
     * Returns average time per character across all runs
     */
    private suspend fun benchmarkTask(
        llmEngine: NexaLlmEngine,
        transcript: String,
        modelType: LlmModelManager.ModelType,
        systemPrompt: String,
        taskName: String
    ): Double = withContext(Dispatchers.IO) {
        val allTimePerChar = mutableListOf<Double>()
        
        // Load the model once for this task
        appendOutput("Loading ${modelType.name} model...\n")
        llmEngine.loadModel(modelType)
        appendOutput("Model loaded.\n\n")
        
        for (length in testLengths) {
            appendOutput("--- Testing length: $length chars ---\n")
            val input = transcript.take(length)
            
            for (runIndex in 1..runsPerLength) {
                appendOutput("Run $runIndex/$runsPerLength: ")
                
                val result = runInference(
                    llmEngine = llmEngine,
                    input = input,
                    systemPrompt = systemPrompt
                )
                
                val timeMs = result.first
                val outputLength = result.second
                val totalInputChars = result.third  // Total input chars including system prompt
                val timePerChar = timeMs / totalInputChars.toDouble()
                allTimePerChar.add(timePerChar)
                
                appendOutput("${timeMs}ms, $totalInputChars input chars, ${outputLength} output tokens, %.4f ms/char\n".format(timePerChar))
            }
            appendOutput("\n")
        }
        
        // Calculate average time per char for this task
        val avgTimePerChar = allTimePerChar.average()
        appendOutput("$taskName average: %.4f ms/char (across ${allTimePerChar.size} runs)\n".format(avgTimePerChar))
        
        avgTimePerChar
    }
    
    /**
     * Run a single inference and measure time
     * Returns (inference time in ms, output token count, total input character count)
     */
    private suspend fun runInference(
        llmEngine: NexaLlmEngine,
        input: String,
        systemPrompt: String
    ): Triple<Long, Int, Int> = withContext(Dispatchers.IO) {
        val wrapper = llmEngine.currentWrapper
            ?: throw IllegalStateException("LLM model not loaded")
        
        var outputTokenCount = 0
        
        // Build chat messages
        val userMessagePrefix = "Summarize or create SOAP note:\n\n"
        val chatMessages = arrayListOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userMessagePrefix + input)
        )
        
        // Calculate total input character count
        val totalInputChars = systemPrompt.length + userMessagePrefix.length + input.length
        
        // Apply chat template
        val templateResult = wrapper.applyChatTemplate(chatMessages.toTypedArray(), null, false)
        if (templateResult.isFailure) {
            throw Exception("Failed to apply chat template: ${templateResult.exceptionOrNull()?.message}")
        }
        val template = templateResult.getOrThrow()
        
        // Measure inference time
        val inferenceTimeMs = measureTimeMillis {
            val genConfig = GenerationConfig(maxTokens = 4096)
            
            wrapper.generateStreamFlow(template.formattedText, genConfig).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        outputTokenCount++
                    }
                    is LlmStreamResult.Completed -> {
                        // Done
                    }
                    is LlmStreamResult.Error -> {
                        throw Exception("Generation error: ${result.throwable.message}")
                    }
                }
            }
        }
        
        // Reset model for next run
        wrapper.reset()
        
        Triple(inferenceTimeMs, outputTokenCount, totalInputChars)
    }
    
    /**
     * Load transcript from assets
     */
    private fun loadTranscript(): String {
        return try {
            assets.open("transcript.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Append text to output TextView on UI thread
     */
    private suspend fun appendOutput(text: String) {
        withContext(Dispatchers.Main) {
            outputText.append(text)
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
