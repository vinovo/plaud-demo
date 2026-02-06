package demo.nexa.plauid.data.mapper

import demo.nexa.plauid.common.formatElapsedTime
import demo.nexa.plauid.data.local.entity.RecordingNoteEntity
import demo.nexa.plauid.domain.model.NoteSource
import demo.nexa.plauid.domain.model.NoteStatus
import demo.nexa.plauid.domain.model.RecordingNote
import demo.nexa.plauid.ui.state.NoteUiState
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mappers between different representations of notes:
 * - Entity (Room database)
 * - Domain (business logic)
 * - UI State (presentation)
 */

// ==================== Entity <-> Domain ====================

fun RecordingNoteEntity.toDomain(): RecordingNote {
    return RecordingNote(
        id = id,
        createdAtEpochMs = createdAtEpochMs,
        title = title,
        audioFileName = audioFileName,
        durationMs = durationMs,
        source = NoteSource.valueOf(source),
        status = NoteStatus.valueOf(status),
        transcriptText = transcriptText,
        summaryText = summaryText,
        errorMessage = errorMessage,
        waveformData = waveformData?.let { parseWaveformData(it) }
    )
}

fun RecordingNote.toEntity(): RecordingNoteEntity {
    return RecordingNoteEntity(
        id = id,
        createdAtEpochMs = createdAtEpochMs,
        title = title,
        audioFileName = audioFileName,
        durationMs = durationMs,
        source = source.name,
        status = status.name,
        transcriptText = transcriptText,
        summaryText = summaryText,
        errorMessage = errorMessage,
        waveformData = waveformData?.let { serializeWaveformData(it) }
    )
}

// ==================== Domain <-> UI State ====================

private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)

fun RecordingNote.toUiState(): NoteUiState {
    val date = Date(createdAtEpochMs)
    val formattedDate = dateFormat.format(date)
    
    val duration = durationMs?.let { formatElapsedTime(it, forceHours = true) } ?: "00:00:00"
    
    val hasTranscript = transcriptText != null
    val isProcessing = status == NoteStatus.TRANSCRIBING || status == NoteStatus.SUMMARIZING
    
    return NoteUiState(
        id = id,
        title = title,
        date = formattedDate,
        duration = duration,
        hasTranscript = hasTranscript,
        isProcessing = isProcessing
    )
}

// ==================== Convenience: Entity -> UI State (direct) ====================

fun RecordingNoteEntity.toUiState(): NoteUiState {
    return this.toDomain().toUiState()
}

// ==================== Waveform Data Serialization ====================

/**
 * Serialize waveform data to JSON string for database storage.
 */
private fun serializeWaveformData(amplitudes: List<Float>): String {
    val json = JSONArray()
    amplitudes.forEach { json.put(it) }
    return json.toString()
}

/**
 * Parse waveform data from JSON string.
 */
private fun parseWaveformData(jsonString: String): List<Float> {
    return try {
        val json = JSONArray(jsonString)
        val result = mutableListOf<Float>()
        for (i in 0 until json.length()) {
            result.add(json.getDouble(i).toFloat())
        }
        result
    } catch (e: Exception) {
        emptyList()
    }
}
