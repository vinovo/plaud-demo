package demo.nexa.clinical_transcription_demo.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcodes audio files between formats.
 * Primary use case: M4A/AAC → WAV PCM for ASR compatibility.
 * Uses streaming decode to avoid loading entire audio into memory.
 */
class AudioTranscoder {
    
    /**
     * Convert an M4A/AAC audio file to WAV PCM format.
     * Uses streaming decode for memory efficiency.
     *
     * @param sourceFile Input M4A file
     * @param outputFile Output WAV file
     * @param deleteSource Whether to delete the source file after successful conversion
     * @return Result containing AudioInfo with actual sample rate, or failure
     */
    fun convertToWav(
        sourceFile: File,
        outputFile: File,
        deleteSource: Boolean = false
    ): Result<AudioInfo> {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var outputStream: RandomAccessFile? = null
        
        return try {
            if (!sourceFile.exists()) {
                return Result.failure(IllegalArgumentException("Source file does not exist"))
            }
            
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)
            
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                extractor.release()
                return Result.failure(IllegalStateException("No audio track found"))
            }
            
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            outputStream = RandomAccessFile(outputFile, "rw")
            outputStream.seek(44)
            
            val audioInfo = streamDecodeToPcm(
                extractor = extractor,
                codec = codec,
                outputStream = outputStream,
                initialSampleRate = sampleRate,
                initialChannelCount = channelCount
            )
            
            val totalDataSize = outputStream.filePointer - 44
            
            outputStream.seek(0)
            val header = createWavHeader(
                dataSize = totalDataSize,
                sampleRate = audioInfo.sampleRate,
                channels = audioInfo.channelCount,
                bitsPerSample = 16
            )
            outputStream.write(header)
            
            outputStream.close()
            codec.stop()
            codec.release()
            extractor.release()
            
            if (deleteSource && outputFile.exists()) {
                sourceFile.delete()
            }
            
            Result.success(audioInfo.copy(file = outputFile))
            
        } catch (e: Exception) {
            outputStream?.close()
            codec?.stop()
            codec?.release()
            extractor?.release()
            outputFile.delete()
            Result.failure(e)
        }
    }
    
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
     * Stream decode PCM and write directly to file.
     * Returns actual audio parameters from decoder output format.
     */
    private fun streamDecodeToPcm(
        extractor: MediaExtractor,
        codec: MediaCodec,
        outputStream: RandomAccessFile,
        initialSampleRate: Int,
        initialChannelCount: Int
    ): AudioInfo {
        val bufferInfo = MediaCodec.BufferInfo()
        var isInputEOS = false
        var isOutputEOS = false
        
        var actualSampleRate = initialSampleRate
        var actualChannelCount = initialChannelCount
        
        val timeoutUs = 10000L
        
        while (!isOutputEOS) {
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
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                presentationTimeUs, 0
                            )
                            extractor.advance()
                        }
                    }
                }
            }
            
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            
            when {
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    actualSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    actualChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    
                    if (outputBuffer != null && bufferInfo.size > 0 && !isCodecConfig) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        outputStream.write(chunk)
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }
        }
        
        return AudioInfo(
            sampleRate = actualSampleRate,
            channelCount = actualChannelCount,
            file = File("")
        )
    }
    
    private fun createWavHeader(
        dataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        
        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            
            // RIFF chunk
            put("RIFF".toByteArray())
            putInt((36 + dataSize).toInt()) // File size - 8
            put("WAVE".toByteArray())
            
            // fmt chunk
            put("fmt ".toByteArray())
            putInt(16) // fmt chunk size
            putShort(1) // Audio format (1 = PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            
            // data chunk
            put("data".toByteArray())
            putInt(dataSize.toInt())
        }
        
        return header.array()
    }
    
    companion object {
        @Volatile
        private var INSTANCE: AudioTranscoder? = null
        
        fun getInstance(): AudioTranscoder {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioTranscoder().also { INSTANCE = it }
            }
        }
    }
}

/**
 * Audio metadata returned after conversion.
 */
data class AudioInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val file: File
)
