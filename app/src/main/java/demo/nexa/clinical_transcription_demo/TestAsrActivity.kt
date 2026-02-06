package demo.nexa.clinical_transcription_demo

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import demo.nexa.clinical_transcription_demo.asr.NexaAsrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Test activity for ASR benchmarking.
 */
class TestAsrActivity : AppCompatActivity() {
    
    private lateinit var outputText: TextView
    private lateinit var startButton: Button
    private lateinit var scrollView: ScrollView
    
    private val testAudioPaths = listOf(
        "/data/local/tmp/jfk.wav",
        "/data/local/tmp/OSR_us_000_0010_16k.wav",
        "/data/local/tmp/YTDown.com_YouTube_CBT-Role-Play-Complete-Session-Social-An_Media_8K4HW6_MvoU_004_144p.mp3"
    )
    
    private val numRuns = 10
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        startButton = Button(this).apply {
            text = "Start ASR Benchmark"
            setOnClickListener { runBenchmark() }
        }
        
        outputText = TextView(this).apply {
            text = "Click 'Start' to begin ASR benchmarking...\n\n"
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
                val asrEngine = NexaAsrEngine.getInstance(applicationContext)
                
                appendOutput("=== ASR BENCHMARK TEST ===\n")
                appendOutput("Audio files: ${testAudioPaths.size}\n")
                appendOutput("Runs per file: $numRuns\n\n")
                
                val allRtfs = mutableListOf<Double>()
                
                for ((index, audioPath) in testAudioPaths.withIndex()) {
                    appendOutput("--- Audio ${index + 1}: $audioPath ---\n")
                    
                    // Check if file exists
                    val audioFile = File(audioPath)
                    if (!audioFile.exists()) {
                        appendOutput("ERROR: File not found!\n\n")
                        continue
                    }
                    
                    // Get audio duration (for RTF calculation)
                    val audioDurationMs = getAudioDuration(audioFile)
                    appendOutput("Audio duration: ${audioDurationMs}ms (${audioDurationMs / 1000.0}s)\n\n")
                    
                    val latencies = mutableListOf<Long>()
                    val rtfs = mutableListOf<Double>()
                    
                    // Run ASR multiple times
                    for (run in 1..numRuns) {
                        appendOutput("Run $run: ")
                        
                        var wasSuccess = false
                        val inferenceTimeMs = withContext(Dispatchers.IO) {
                            measureTimeMillis {
                                val result = asrEngine.transcribe(audioPath, "en")
                                result.onSuccess {
                                    wasSuccess = true
                                }.onFailure { error ->
                                    appendOutput("ERROR - ${error.message}\n")
                                }
                            }
                        }
                        
                        if (!wasSuccess) {
                            continue  // Skip this run if it failed
                        }
                        
                        latencies.add(inferenceTimeMs)
                        
                        // RTF = inference_time / audio_duration
                        val rtf = inferenceTimeMs.toDouble() / audioDurationMs.toDouble()
                        rtfs.add(rtf)
                        allRtfs.add(rtf)
                        
                        appendOutput("${inferenceTimeMs}ms, RTF=${String.format("%.3f", rtf)}\n")
                    }
                    
                    // Calculate averages for this audio
                    val avgLatency = latencies.average()
                    val avgRtf = rtfs.average()
                    
                    appendOutput("\nAudio ${index + 1} Summary:\n")
                    appendOutput("  Avg Latency: ${String.format("%.2f", avgLatency)}ms\n")
                    appendOutput("  Avg RTF: ${String.format("%.3f", avgRtf)}\n")
                    appendOutput("  Min RTF: ${String.format("%.3f", rtfs.minOrNull() ?: 0.0)}\n")
                    appendOutput("  Max RTF: ${String.format("%.3f", rtfs.maxOrNull() ?: 0.0)}\n\n")
                    
                    // Print sample result structure for this audio
                    printSampleResult(asrEngine, audioPath, index + 1)
                }
                
                // Overall summary
                if (allRtfs.isNotEmpty()) {
                    appendOutput("=== OVERALL SUMMARY ===\n")
                    appendOutput("Total runs: ${allRtfs.size}\n")
                    appendOutput("Average RTF: ${String.format("%.3f", allRtfs.average())}\n")
                    appendOutput("Min RTF: ${String.format("%.3f", allRtfs.minOrNull() ?: 0.0)}\n")
                    appendOutput("Max RTF: ${String.format("%.3f", allRtfs.maxOrNull() ?: 0.0)}\n\n")
                }
                
                // Test timestamp intervals
                appendOutput("=== TESTING TIMESTAMP INTERVALS ===\n")
                testTimestampIntervals(asrEngine)
                
                appendOutput("\n=== BENCHMARK COMPLETE ===\n")
                
            } catch (e: Exception) {
                appendOutput("\nFATAL ERROR: ${e.message}\n")
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    startButton.isEnabled = true
                }
            }
        }
    }
    
    private suspend fun testTimestampIntervals(asrEngine: NexaAsrEngine) {
        withContext(Dispatchers.IO) {
            // Use first audio file that exists
            val testFile = testAudioPaths.firstOrNull { File(it).exists() }
            if (testFile == null) {
                appendOutput("No valid audio file found for timestamp test\n")
                return@withContext
            }
            
            appendOutput("Testing with: $testFile\n\n")
            
            // We need to access the raw AsrTranscribeOutput to get timestamps
            // Currently NexaAsrEngine.transcribe() only returns the transcript string
            // Let's make a direct call to examine the full output
            
            try {
                val asrWrapper = getAsrWrapperForInspection(asrEngine)
                if (asrWrapper != null) {
                    val transcribeResult = asrWrapper.transcribe(
                        com.nexa.sdk.bean.AsrTranscribeInput(
                            audioPath = testFile,
                            language = "en",
                            config = null
                        )
                    )
                    
                    transcribeResult.onSuccess { output ->
                        appendOutput("Transcript: ${output.result.transcript?.take(200)}...\n\n")
                        
                        // Examine timestamps
                        val timestamps = output.result.timestamps
                        if (timestamps != null && timestamps.isNotEmpty()) {
                            appendOutput("Timestamps found: ${timestamps.size} entries\n")
                            
                            // Calculate intervals between consecutive timestamps
                            val intervals = mutableListOf<Float>()
                            for (i in 1 until timestamps.size) {
                                intervals.add(timestamps[i] - timestamps[i-1])
                            }
                            
                            if (intervals.isNotEmpty()) {
                                val avgInterval = intervals.average()
                                val minInterval = intervals.minOrNull() ?: 0f
                                val maxInterval = intervals.maxOrNull() ?: 0f
                                
                                appendOutput("Average timestamp interval: ${String.format("%.1f", avgInterval)}ms\n")
                                appendOutput("Min interval: ${String.format("%.1f", minInterval)}ms\n")
                                appendOutput("Max interval: ${String.format("%.1f", maxInterval)}ms\n\n")
                                
                                // Show first 10 timestamps and intervals
                                appendOutput("First 10 timestamps:\n")
                                for (i in 0 until minOf(10, timestamps.size)) {
                                    val intervalStr = if (i > 0) {
                                        " (${String.format("%.1f", timestamps[i] - timestamps[i-1])}ms)"
                                    } else ""
                                    appendOutput("  [$i] ${String.format("%.1f", timestamps[i])}ms$intervalStr\n")
                                }
                            }
                        } else {
                            appendOutput("No timestamps found in result\n")
                            appendOutput("Timestamps may not be enabled by default\n")
                        }
                        
                        // Show profiling data
                        appendOutput("\nProfiling data from SDK:\n")
                        appendOutput("  TTFT: ${String.format("%.2f", output.profileData.ttftMs)}ms\n")
                        appendOutput("  Prompt time: ${String.format("%.2f", output.profileData.promptTimeMs)}ms\n")
                        appendOutput("  Decode time: ${String.format("%.2f", output.profileData.decodeTimeMs)}ms\n")
                        appendOutput("  Audio duration: ${output.profileData.audioDurationMs}ms\n")
                        appendOutput("  SDK RTF: ${String.format("%.3f", output.profileData.realTimeFactor)}\n")
                        appendOutput("  Prefill speed: ${String.format("%.2f", output.profileData.prefillSpeed)} tokens/s\n")
                        appendOutput("  Decoding speed: ${String.format("%.2f", output.profileData.decodingSpeed)} tokens/s\n")
                        
                    }.onFailure { error ->
                        appendOutput("Timestamp test failed: ${error.message}\n")
                    }
                } else {
                    appendOutput("Could not access ASR wrapper for detailed inspection\n")
                }
            } catch (e: Exception) {
                appendOutput("Error examining timestamps: ${e.message}\n")
                e.printStackTrace()
            }
        }
    }
    
    // Helper to access the raw AsrWrapper (hacky but this is a test script)
    private suspend fun getAsrWrapperForInspection(asrEngine: NexaAsrEngine): com.nexa.sdk.AsrWrapper? {
        return try {
            // Trigger initialization if needed
            asrEngine.ensureReady()
            
            // Use reflection to access the private asrWrapper field
            val field = asrEngine.javaClass.getDeclaredField("asrWrapper")
            field.isAccessible = true
            field.get(asrEngine) as? com.nexa.sdk.AsrWrapper
        } catch (e: Exception) {
            appendOutput("Warning: Could not access AsrWrapper via reflection: ${e.message}\n")
            null
        }
    }
    
    /**
     * Print a sample result structure to show what the ASR output looks like
     */
    private suspend fun printSampleResult(asrEngine: NexaAsrEngine, audioPath: String, audioNum: Int) {
        withContext(Dispatchers.IO) {
            appendOutput("--- Sample Result Structure (Audio $audioNum) ---\n")
            
            try {
                val asrWrapper = getAsrWrapperForInspection(asrEngine)
                if (asrWrapper != null) {
                    val transcribeResult = asrWrapper.transcribe(
                        com.nexa.sdk.bean.AsrTranscribeInput(
                            audioPath = audioPath,
                            language = "en",
                            config = null
                        )
                    )
                    
                    transcribeResult.onSuccess { output ->
                        // Show result structure
                        appendOutput("AsrTranscribeOutput {\n")
                        appendOutput("  result: AsrResult {\n")
                        
                        // Transcript
                        val transcript = output.result.transcript
                        if (transcript != null) {
                            val preview = if (transcript.length > 100) {
                                "${transcript.take(100)}..."
                            } else {
                                transcript
                            }
                            appendOutput("    transcript: \"$preview\"\n")
                            appendOutput("    transcript.length: ${transcript.length}\n")
                        } else {
                            appendOutput("    transcript: null\n")
                        }
                        
                        // Confidence scores
                        val confidenceScores = output.result.confidenceScores
                        if (confidenceScores != null && confidenceScores.isNotEmpty()) {
                            appendOutput("    confidenceScores: [${confidenceScores.size} entries]\n")
                            appendOutput("      First 5: ${confidenceScores.take(5)}\n")
                        } else {
                            appendOutput("    confidenceScores: ${confidenceScores?.size ?: "null"}\n")
                        }
                        
                        // Timestamps
                        val timestamps = output.result.timestamps
                        if (timestamps != null && timestamps.isNotEmpty()) {
                            appendOutput("    timestamps: [${timestamps.size} entries]\n")
                            appendOutput("      First 10: ${timestamps.take(10)}\n")
                            
                            // Calculate intervals
                            if (timestamps.size > 1) {
                                val intervals = (1 until minOf(10, timestamps.size)).map { i ->
                                    timestamps[i] - timestamps[i - 1]
                                }
                                appendOutput("      Intervals: $intervals\n")
                            }
                        } else {
                            appendOutput("    timestamps: ${timestamps?.size ?: "null"}\n")
                        }
                        
                        appendOutput("  }\n")
                        
                        // Profile data
                        appendOutput("  profileData: ProfilingData {\n")
                        appendOutput("    ttftMs: ${String.format("%.2f", output.profileData.ttftMs)}\n")
                        appendOutput("    promptTimeMs: ${String.format("%.2f", output.profileData.promptTimeMs)}\n")
                        appendOutput("    decodeTimeMs: ${String.format("%.2f", output.profileData.decodeTimeMs)}\n")
                        appendOutput("    audioDurationMs: ${output.profileData.audioDurationMs}\n")
                        appendOutput("    realTimeFactor: ${String.format("%.3f", output.profileData.realTimeFactor)}\n")
                        appendOutput("    prefillSpeed: ${String.format("%.2f", output.profileData.prefillSpeed)} tokens/s\n")
                        appendOutput("    decodingSpeed: ${String.format("%.2f", output.profileData.decodingSpeed)} tokens/s\n")
                        appendOutput("    promptTokens: ${output.profileData.promptTokens}\n")
                        appendOutput("    generatedTokens: ${output.profileData.generatedTokens}\n")
                        appendOutput("    stopReason: \"${output.profileData.stopReason}\"\n")
                        appendOutput("  }\n")
                        appendOutput("}\n\n")
                        
                    }.onFailure { error ->
                        appendOutput("Failed to get sample result: ${error.message}\n\n")
                    }
                } else {
                    appendOutput("Could not access AsrWrapper for sample result\n\n")
                }
            } catch (e: Exception) {
                appendOutput("Error printing sample result: ${e.message}\n\n")
            }
        }
    }
    
    private fun getAudioDuration(audioFile: File): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(audioFile.absolutePath)
            val duration = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            duration
        } catch (e: Exception) {
            0L
        }
    }
    
    private suspend fun appendOutput(text: String) {
        withContext(Dispatchers.Main) {
            outputText.append(text)
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
