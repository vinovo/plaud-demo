package demo.nexa.clinical_transcription_demo.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for writing WAV file headers.
 */
object WavWriter {
    
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
