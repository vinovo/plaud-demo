package demo.nexa.plauid.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Simulates progress with non-linear progression for better UX.
 * Uses an ease-out curve where progress starts fast and slows down near completion.
 * 
 * Usage: Pass expectedDurationMs based on actual task duration estimate.
 * For ASR transcription: expectedDurationMs = audio_duration * model_rtf
 * Example: 10-second audio with RTF=0.2 → expectedDurationMs = 10000 * 0.2 = 2000ms
 */
object ProgressSimulator {
    
    /**
     * Generate a flow of progress values from 0 to 99 over the specified duration.
     * Progress follows a non-linear curve for more natural appearance.
     * Each increment has normally-distributed variation, resulting in natural
     * completion time variation around the expected duration.
     * NOTE: Progress caps at 99%, never reaching 100% (stuck at 99).
     * 
     * @param expectedDurationMs Expected time to reach 99% in milliseconds
     * @param updateIntervalMs How often to emit progress updates (default: 100ms)
     * @return Flow emitting progress values from 0 to 99
     */
    fun simulateProgress(
        expectedDurationMs: Long = 5000L,
        updateIntervalMs: Long = 100L
    ): Flow<Int> = flow {
        val totalSteps = (expectedDurationMs / updateIntervalMs).toInt()
        
        for (step in 0..totalSteps) {
            val linearProgress = step.toFloat() / totalSteps.toFloat()
            
            // Apply ease-out curve: starts fast (60% in first 40% of time), then slows down
            // Using quadratic ease-out: progress = 1 - (1 - t)^2
            val curvedProgress = 1f - (1f - linearProgress).pow(2f)
            
            // Convert to percentage (0-99, capped at 99)
            val percentage = (curvedProgress * 100f).toInt().coerceIn(0, 99)
            
            emit(percentage)
            
            if (percentage >= 99) {
                break
            }
            
            // Add normally-distributed variation to each delay
            // Standard deviation is 15% of update interval
            // Multiple small variations sum to produce overall normal distribution
            val delayVariation = Random.nextGaussian(
                mean = 0.0,
                stdDev = updateIntervalMs * 0.15
            )
            val actualDelay = (updateIntervalMs + delayVariation.toLong())
                .coerceAtLeast(10)  // Minimum 10ms delay
            
            delay(actualDelay)
        }
        
        // Ensure we emit 99 at the end (stuck at 99%)
        emit(99)
    }
    
    /**
     * Generate a flow with custom easing function.
     * NOTE: Progress caps at 99%, never reaching 100% (stuck at 99).
     * 
     * @param expectedDurationMs Expected time to reach 99%
     * @param updateIntervalMs Update frequency
     * @param easingFunction Custom easing function (0.0-1.0 input -> 0.0-1.0 output)
     */
    fun simulateProgressWithEasing(
        expectedDurationMs: Long = 5000L,
        updateIntervalMs: Long = 100L,
        easingFunction: (Float) -> Float = { t -> 1f - (1f - t).pow(2f) }
    ): Flow<Int> = flow {
        val totalSteps = (expectedDurationMs / updateIntervalMs).toInt()
        
        for (step in 0..totalSteps) {
            val linearProgress = step.toFloat() / totalSteps.toFloat()
            val easedProgress = easingFunction(linearProgress)
            val percentage = (easedProgress * 100f).toInt().coerceIn(0, 99)
            
            emit(percentage)
            
            if (percentage >= 99) break
            
            // Add normally-distributed variation to each delay
            val delayVariation = Random.nextGaussian(
                mean = 0.0,
                stdDev = updateIntervalMs * 0.15
            )
            val actualDelay = (updateIntervalMs + delayVariation.toLong())
                .coerceAtLeast(10)
            
            delay(actualDelay)
        }
        
        emit(99)
    }
    
    /**
     * Generate a normally-distributed random number using Box-Muller transform.
     * 
     * @param mean Mean of the distribution
     * @param stdDev Standard deviation of the distribution
     * @return A random number from the normal distribution
     */
    private fun Random.nextGaussian(mean: Double = 0.0, stdDev: Double = 1.0): Double {
        // Box-Muller transform to convert uniform random to normal distribution
        val u1 = nextDouble()
        val u2 = nextDouble()
        val z0 = sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
        return z0 * stdDev + mean
    }
}
