package demo.nexa.plauid.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for writing WAV file headers.
 * WAV format: RIFF header + fmt chunk + data chunk + PCM samples
 */
object WavWriter {
    
    /**
     * Write a WAV header to a file that already contains PCM data.
     * This updates the file in-place by writing the header at the beginning.
     *
     * @param file File containing PCM data (will be updated with WAV header)
     * @param sampleRate Sample rate in Hz (e.g., 16000)
     * @param channels Number of channels (1 for mono, 2 for stereo)
     * @param bitsPerSample Bits per sample (typically 16)
     */
    fun writeWavHeader(
        file: File,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataSize = raf.length() - 44 // Subtract header size if it exists
            val actualDataSize = maxOf(0, dataSize) // Ensure non-negative
            
            raf.seek(0)
            
            // Write WAV header
            val header = createWavHeader(
                dataSize = actualDataSize,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample
            )
            
            raf.write(header)
        }
    }
    
    /**
     * Create a WAV file from PCM data.
     *
     * @param outputFile Output WAV file
     * @param pcmData PCM audio data (16-bit little-endian)
     * @param sampleRate Sample rate in Hz
     * @param channels Number of channels
     * @param bitsPerSample Bits per sample
     */
    fun createWavFile(
        outputFile: File,
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        outputFile.outputStream().use { out ->
            // Write header
            val header = createWavHeader(
                dataSize = pcmData.size.toLong(),
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample
            )
            out.write(header)
            
            // Write PCM data
            out.write(pcmData)
        }
    }
    
    /**
     * Create a 44-byte WAV header.
     */
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
}
