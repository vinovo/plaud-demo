package demo.nexa.clinical_transcription_demo.presentation

import android.app.Application
import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import demo.nexa.clinical_transcription_demo.common.formatDateForDisplay
import demo.nexa.clinical_transcription_demo.data.audio.AudioFileManager
import demo.nexa.clinical_transcription_demo.data.local.AppDatabase
import demo.nexa.clinical_transcription_demo.data.repository.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * ViewModel for MainActivity that handles audio import flow.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val audioFileManager = AudioFileManager(application)
    private val repository = NotesRepository(database, audioFileManager, application)
    private val contentResolver: ContentResolver = application.contentResolver

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()
    
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /**
     * Import an audio file from the given URI.
     * Shows loading UI during import and emits UiEvent.ShowToast on success or error.
     */
    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val (duration, extension) = withContext(Dispatchers.IO) {
                    extractMetadata(uri)
                }

                val title = generateImportTitle()

                withContext(Dispatchers.IO) {
                    repository.createImportedNote(
                        title = title,
                        sourceUri = uri,
                        extension = extension,
                        durationMs = duration
                    )
                }

                _uiEvents.emit(UiEvent.ShowToast("Audio imported successfully"))
            } catch (e: Exception) {
                _uiEvents.emit(UiEvent.ShowToast("Failed to import audio: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

    /**
     * Extract duration and file extension from the audio URI.
     * Returns Pair(durationMs, extension)
     */
    private fun extractMetadata(uri: Uri): Pair<Long, String> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(getApplication(), uri)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val extension = detectExtension(uri, retriever)

            return Pair(durationMs, extension)
        } finally {
            retriever.release()
        }
    }

    /**
     * Detect file extension using multiple fallback strategies:
     * 1. ContentResolver.getType() for MIME type
     * 2. DISPLAY_NAME from OpenableColumns
     * 3. MediaMetadataRetriever MIME type
     * 4. Default to "m4a"
     */
    private fun detectExtension(uri: Uri, retriever: MediaMetadataRetriever): String {
        contentResolver.getType(uri)?.let { mimeType ->
            mimeTypeToExtension(mimeType)?.let { return it }
        }

        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val displayName = cursor.getString(nameIndex)
                    displayName?.substringAfterLast('.', "")?.lowercase()?.let { ext ->
                        if (ext.isNotEmpty() && ext in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac")) {
                            return ext
                        }
                    }
                }
            }
        }

        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { mimeType ->
            mimeTypeToExtension(mimeType)?.let { return it }
        }

        return "m4a"
    }

    /**
     * Convert MIME type to file extension.
     */
    private fun mimeTypeToExtension(mimeType: String): String? {
        return when {
            mimeType.contains("wav", ignoreCase = true) -> "wav"
            mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "mp3"
            mimeType.contains("mp4", ignoreCase = true) || mimeType.contains("m4a", ignoreCase = true) -> "m4a"
            mimeType.contains("aac", ignoreCase = true) -> "aac"
            mimeType.contains("ogg", ignoreCase = true) -> "ogg"
            mimeType.contains("flac", ignoreCase = true) -> "flac"
            else -> null
        }
    }

    /**
     * Generate a timestamped title for imported audio.
     */
    private fun generateImportTitle(): String {
        val timestamp = formatDateForDisplay(Date())
        return "Imported $timestamp"
    }

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
    }
}
