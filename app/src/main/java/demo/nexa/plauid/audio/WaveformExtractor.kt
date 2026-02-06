package demo.nexa.plauid.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.abs

/**
 * Extracts waveform data from audio files for visualization.
 */
class WaveformExtractor {
    
    /**
     * Extract waveform amplitudes from an audio file.
     * Returns normalized amplitude values (0.0-1.0) at regular intervals.
     *
     * @param audioFile Audio file (M4A, WAV, MP3, etc.)
     * @param targetSampleCount Number of waveform samples to extract
     * @return Result containing list of normalized amplitudes or failure
     */
    fun extractWaveform(
        audioFile: File,
        targetSampleCount: Int = 100
    ): Result<List<Float>> {
        return try {
            if (!audioFile.exists()) {
                return Result.failure(IllegalArgumentException("Audio file does not exist"))
            }
            
            val extractor = MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)
            
            // Find audio track
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                extractor.release()
                return Result.failure(IllegalStateException("No audio track found"))
            }
            
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            
            // Create decoder
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            // Decode and compute peaks
            val pcmSamples = decodeToPcmSamples(extractor, codec)
            
            // Release resources
            codec.stop()
            codec.release()
            extractor.release()
            
            // Downsample to target number of peaks
            val peaks = computePeaks(pcmSamples, targetSampleCount)
            
            Result.success(peaks)
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Find the audio track index.
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }
    
    /**
     * Decode audio to 16-bit PCM samples with streaming and proper buffer handling.
     * Downsamples on-the-fly to avoid loading entire audio into memory.
     */
    private fun decodeToPcmSamples(extractor: MediaExtractor, codec: MediaCodec): ShortArray {
        val samples = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isInputEOS = false
        var isOutputEOS = false
        val timeoutUs = 10000L
        
        // For long files, downsample during decode to save memory
        var sampleCounter = 0
        val downsampleFactor = 4 // Keep every 4th sample
        
        while (!isOutputEOS) {
            // Feed input
            if (!isInputEOS) {
                val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isInputEOS = true
                        } else {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }
            }
            
            // Get output
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            
            when {
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Format changed, continue
                }
                
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    
                    // Skip codec config buffers
                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    
                    if (outputBuffer != null && bufferInfo.size > 0 && !isCodecConfig) {
                        // Respect offset and size from bufferInfo
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        
                        // Downsample to save memory (keep every Nth sample)
                        while (outputBuffer.remaining() >= 2) {
                            val sample = outputBuffer.short
                            if (sampleCounter % downsampleFactor == 0) {
                                samples.add(sample)
                            }
                            sampleCounter++
                        }
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }
        }
        
        return samples.toShortArray()
    }
    
    /**
     * Compute peak amplitudes by downsampling PCM data.
     * Divides audio into bins and finds the max absolute amplitude in each bin.
     *
     * @param samples Raw PCM samples (16-bit)
     * @param targetCount Number of peaks to compute
     * @return Normalized peak amplitudes (0.0-1.0)
     */
    private fun computePeaks(samples: ShortArray, targetCount: Int): List<Float> {
        if (samples.isEmpty()) return emptyList()
        if (targetCount <= 0) return emptyList()
        
        val peaks = mutableListOf<Float>()
        val samplesPerBin = maxOf(1, samples.size / targetCount)
        
        for (i in 0 until targetCount) {
            val startIdx = i * samplesPerBin
            val endIdx = minOf(startIdx + samplesPerBin, samples.size)
            
            if (startIdx >= samples.size) break
            
            // Find max absolute amplitude in this bin
            var maxAmplitude = 0
            for (j in startIdx until endIdx) {
                val absValue = abs(samples[j].toInt())
                if (absValue > maxAmplitude) {
                    maxAmplitude = absValue
                }
            }
            
            // Normalize to 0.0-1.0 (Short.MAX_VALUE = 32767)
            val normalized = maxAmplitude.toFloat() / Short.MAX_VALUE.toFloat()
            peaks.add(normalized.coerceIn(0f, 1f))
        }
        
        return peaks
    }
    
    companion object {
        @Volatile
        private var INSTANCE: WaveformExtractor? = null
        
        fun getInstance(): WaveformExtractor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WaveformExtractor().also { INSTANCE = it }
            }
        }
    }
}
