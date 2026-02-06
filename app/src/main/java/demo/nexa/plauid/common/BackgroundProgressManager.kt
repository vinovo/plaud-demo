package demo.nexa.plauid.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages background progress simulations that survive ViewModel lifecycle.
 * Provides centralized progress tracking for transcription and summary generation.
 * 
 * Progress simulations continue even when the user navigates away from a note,
 * and can be resumed when returning to the note.
 */
class BackgroundProgressManager private constructor() {
    
    enum class ProgressType {
        TRANSCRIPTION,
        SUMMARY
    }
    
    private data class ProgressKey(val noteId: String, val type: ProgressType)
    
    // Map of (noteId, type) -> current progress (0-99)
    private val progressMap = ConcurrentHashMap<ProgressKey, MutableStateFlow<Int>>()
    
    // Map of (noteId, type) -> coroutine job
    private val progressJobs = ConcurrentHashMap<ProgressKey, Job>()
    
    // Background scope with SupervisorJob (survives individual job failures)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * Start progress simulation for a transcription job.
     * 
     * @param noteId The note being transcribed
     * @param audioDurationMs Audio duration in milliseconds
     * @param rtf Real-Time Factor (inference_time / audio_duration)
     */
    fun startTranscriptionProgress(
        noteId: String,
        audioDurationMs: Long,
        rtf: Double = 0.2
    ) {
        val key = ProgressKey(noteId, ProgressType.TRANSCRIPTION)
        
        // Cancel existing job if any
        stopProgress(noteId, ProgressType.TRANSCRIPTION)
        
        val expectedMs = (audioDurationMs * rtf).toLong().coerceAtLeast(1000L)
        val progressFlow = MutableStateFlow(0)
        progressMap[key] = progressFlow
        
        progressJobs[key] = scope.launch {
            ProgressSimulator.simulateProgress(
                expectedDurationMs = expectedMs,
                updateIntervalMs = 100L
            ).collect { progress ->
                progressFlow.value = progress
            }
        }
    }
    
    /**
     * Start progress simulation for summary generation (short transcript: SOAP only).
     * 
     * @param noteId The note being summarized
     * @param transcriptLength Input transcript length
     * @param msPerChar Measured inference speed (ms/char)
     * @param promptLength Combined system prompt + user prefix length
     */
    fun startSummarySinglePhaseProgress(
        noteId: String,
        transcriptLength: Int,
        msPerChar: Double,
        promptLength: Int
    ) {
        val key = ProgressKey(noteId, ProgressType.SUMMARY)
        
        stopProgress(noteId, ProgressType.SUMMARY)
        
        val totalInputChars = promptLength + transcriptLength
        val expectedMs = (totalInputChars * msPerChar).toLong()
        val progressFlow = MutableStateFlow(0)
        progressMap[key] = progressFlow
        
        progressJobs[key] = scope.launch {
            ProgressSimulator.simulateProgress(
                expectedDurationMs = expectedMs,
                updateIntervalMs = 100L
            ).collect { progress ->
                progressFlow.value = progress
            }
        }
    }
    
    /**
     * Start Phase 1 of summary progress (0-50%): Section summarization.
     * 
     * @param noteId The note being summarized
     * @param transcriptLength Total transcript length
     * @param segmentSize Size of each segment (e.g., 2000)
     * @param msPerChar Measured inference speed for summarizer (ms/char)
     * @param promptLength Combined system prompt + user prefix length for summarizer
     */
    fun startSummaryPhase1Progress(
        noteId: String,
        transcriptLength: Int,
        segmentSize: Int,
        msPerChar: Double,
        promptLength: Int
    ) {
        val key = ProgressKey(noteId, ProgressType.SUMMARY)
        
        stopProgress(noteId, ProgressType.SUMMARY)
        
        // Calculate total summarizer time
        val numSegments = (transcriptLength + segmentSize - 1) / segmentSize
        var totalTimeMs = 0.0
        var remainingChars = transcriptLength
        
        repeat(numSegments) {
            val segSize = minOf(segmentSize, remainingChars)
            totalTimeMs += (promptLength + segSize) * msPerChar
            remainingChars -= segSize
        }
        
        val progressFlow = MutableStateFlow(0)
        progressMap[key] = progressFlow
        
        progressJobs[key] = scope.launch {
            ProgressSimulator.simulateProgress(
                expectedDurationMs = totalTimeMs.toLong(),
                updateIntervalMs = 100L
            ).collect { progress ->
                // Map 0-99 to 0-50
                val mapped = (progress * 50 / 99).coerceIn(0, 50)
                progressFlow.value = mapped
            }
        }
    }
    
    /**
     * Transition to Phase 2 of summary progress (50-99%): SOAP creation.
     * 
     * @param noteId The note being summarized
     * @param summaryLength Actual length of summaries from Phase 1
     * @param msPerChar Measured inference speed for SOAP creator (ms/char)
     * @param promptLength Combined system prompt + user prefix length for SOAP
     */
    fun startSummaryPhase2Progress(
        noteId: String,
        summaryLength: Int,
        msPerChar: Double,
        promptLength: Int
    ) {
        val key = ProgressKey(noteId, ProgressType.SUMMARY)
        
        // Cancel phase 1 job if running
        progressJobs.remove(key)?.cancel()
        
        val totalInputChars = promptLength + summaryLength
        val expectedMs = (totalInputChars * msPerChar).toLong()
        
        // Get existing progress flow or create new one
        val progressFlow = progressMap.getOrPut(key) { MutableStateFlow(50) }
        progressFlow.value = 50  // Snap to 50%
        
        progressJobs[key] = scope.launch {
            ProgressSimulator.simulateProgress(
                expectedDurationMs = expectedMs,
                updateIntervalMs = 100L
            ).collect { progress ->
                // Map 0-99 to 50-99 (progress * 49 / 99 ensures we reach 99)
                val mapped = 50 + (progress * 49 / 99).coerceIn(0, 49)
                progressFlow.value = mapped
            }
        }
    }
    
    /**
     * Stop and remove progress simulation for a note and type.
     */
    fun stopProgress(noteId: String, type: ProgressType) {
        val key = ProgressKey(noteId, type)
        progressJobs.remove(key)?.cancel()
        progressMap.remove(key)
    }
    
    /**
     * Observe current progress for a note and type.
     * Returns null if no progress tracking is active for this note/type.
     */
    fun observeProgress(noteId: String, type: ProgressType): StateFlow<Int>? {
        val key = ProgressKey(noteId, type)
        return progressMap[key]?.asStateFlow()
    }
    
    /**
     * Get current progress value (0-99) or null if not tracking.
     */
    fun getCurrentProgress(noteId: String, type: ProgressType): Int? {
        val key = ProgressKey(noteId, type)
        return progressMap[key]?.value
    }
    
    companion object {
        @Volatile
        private var INSTANCE: BackgroundProgressManager? = null
        
        fun getInstance(): BackgroundProgressManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackgroundProgressManager().also { INSTANCE = it }
            }
        }
    }
}
