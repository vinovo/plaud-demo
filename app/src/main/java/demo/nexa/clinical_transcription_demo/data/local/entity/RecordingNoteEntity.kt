package demo.nexa.clinical_transcription_demo.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting recording notes.
 */
@Entity(tableName = "recording_notes")
data class RecordingNoteEntity(
    @PrimaryKey
    val id: String,
    val createdAtEpochMs: Long,
    val title: String,
    val audioFileName: String,
    val durationMs: Long?,
    val source: String,
    val status: String,
    val transcriptText: String?,
    val summaryText: String?,
    val errorMessage: String?,
    val waveformData: String? = null
)
