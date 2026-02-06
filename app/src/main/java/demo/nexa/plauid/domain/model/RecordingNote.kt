package demo.nexa.plauid.domain.model

/**
 * Domain model for a recording note.
 * This is the "clean" representation used throughout the app,
 * independent of database or UI concerns.
 */
data class RecordingNote(
    val id: String,
    val createdAtEpochMs: Long,
    val title: String,
    val audioFileName: String,
    val durationMs: Long?,
    val source: NoteSource,
    val status: NoteStatus,
    val transcriptText: String?,
    val summaryText: String?,
    val errorMessage: String?,
    val waveformData: List<Float>? = null // Normalized amplitudes for playback visualization
)
