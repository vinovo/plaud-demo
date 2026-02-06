package demo.nexa.plauid.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting recording notes.
 * Single-table design keeps things simple for a sample app.
 */
@Entity(tableName = "recording_notes")
data class RecordingNoteEntity(
    @PrimaryKey
    val id: String,
    
    val createdAtEpochMs: Long,
    
    val title: String,
    
    /**
     * File name (e.g., "abc-123.m4a") relative to the notes_audio/ folder.
     * The full path is constructed at runtime: filesDir/notes_audio/<audioFileName>
     */
    val audioFileName: String,
    
    /**
     * Duration in milliseconds, extracted from audio metadata.
     * Null if not yet determined.
     */
    val durationMs: Long?,
    
    /**
     * Source of the audio: "RECORDED" or "IMPORTED"
     */
    val source: String,
    
    /**
     * Processing status: "NEW", "TRANSCRIBING", "SUMMARIZING", "DONE", "ERROR"
     */
    val status: String,
    
    /**
     * Transcription text. Null until transcription completes.
     */
    val transcriptText: String?,
    
    /**
     * Summary text. Null until summary generation completes.
     */
    val summaryText: String?,
    
    /**
     * Error message if processing failed. Null otherwise.
     */
    val errorMessage: String?,
    
    /**
     * Waveform amplitude data for visualization (JSON array of floats 0.0-1.0).
     * Null until computed. Used for playback waveform display.
     */
    val waveformData: String? = null
)
