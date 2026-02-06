package demo.nexa.clinical_transcription_demo.data.mapper

import demo.nexa.clinical_transcription_demo.common.formatElapsedTime
import demo.nexa.clinical_transcription_demo.data.local.entity.RecordingNoteEntity
import demo.nexa.clinical_transcription_demo.domain.model.NoteSource
import demo.nexa.clinical_transcription_demo.domain.model.NoteStatus
import demo.nexa.clinical_transcription_demo.domain.model.RecordingNote
import demo.nexa.clinical_transcription_demo.ui.state.NoteUiState
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

fun RecordingNoteEntity.toUiState(): NoteUiState {
    return this.toDomain().toUiState()
}

private fun serializeWaveformData(amplitudes: List<Float>): String {
    val json = JSONArray()
    amplitudes.forEach { json.put(it) }
    return json.toString()
}

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
