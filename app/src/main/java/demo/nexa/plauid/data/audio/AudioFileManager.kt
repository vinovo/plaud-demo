package demo.nexa.plauid.data.audio

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Manages audio file storage in the app's private directory.
 * All audio files (recorded or imported) are stored in filesDir/notes_audio/
 */
class AudioFileManager(private val context: Context) {
    
    private val audioDir: File by lazy {
        File(context.filesDir, AUDIO_FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * Get the audio directory (creating it if needed).
     */
    fun getAudioDirectory(): File = audioDir
    
    /**
     * Get the full file path for a given audio file name.
     * @param fileName e.g., "abc-123.m4a"
     * @return Full File object
     */
    fun getAudioFile(fileName: String): File {
        return File(audioDir, fileName)
    }
    
    /**
     * Check if an audio file exists.
     */
    fun audioFileExists(fileName: String): Boolean {
        return getAudioFile(fileName).exists()
    }
    
    /**
     * Create a new file for recording.
     * @param noteId The note ID to use as base name
     * @param extension File extension (e.g., "m4a", "wav")
     * @return File ready for writing
     */
    fun createRecordingFile(noteId: String, extension: String = "m4a"): File {
        return File(audioDir, "$noteId.$extension")
    }
    
    /**
     * Create a WAV file path for a given note ID.
     * @param noteId The note ID
     * @return File for WAV output
     */
    fun createWavFile(noteId: String): File {
        return File(audioDir, "$noteId.wav")
    }
    
    /**
     * Copy an imported audio file from a content URI to app storage.
     * @param sourceUri The content:// URI of the source audio
     * @param noteId The note ID to use as base name
     * @param extension File extension (e.g., "m4a", "mp3", "wav")
     * @return The file name (not full path) of the copied file
     * @throws IOException if copy fails
     */
    @Throws(IOException::class)
    fun copyImportedAudio(sourceUri: Uri, noteId: String, extension: String = "m4a"): String {
        val fileName = "$noteId.$extension"
        val destFile = getAudioFile(fileName)
        
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Failed to open input stream for URI: $sourceUri")
        
        return fileName
    }
    
    /**
     * Delete an audio file.
     * @param fileName The file name to delete
     * @return true if deleted successfully, false otherwise
     */
    fun deleteAudioFile(fileName: String): Boolean {
        val file = getAudioFile(fileName)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
    
    /**
     * Get the total size of all audio files in bytes.
     */
    fun getTotalAudioSize(): Long {
        return audioDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    /**
     * Delete all audio files (use with caution).
     * Useful for testing or full data reset.
     */
    fun deleteAllAudioFiles(): Int {
        val files = audioDir.listFiles() ?: return 0
        var deletedCount = 0
        files.forEach { file ->
            if (file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }
    
    companion object {
        private const val AUDIO_FOLDER_NAME = "notes_audio"
        
        @Volatile
        private var INSTANCE: AudioFileManager? = null
        
        fun getInstance(context: Context): AudioFileManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioFileManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
