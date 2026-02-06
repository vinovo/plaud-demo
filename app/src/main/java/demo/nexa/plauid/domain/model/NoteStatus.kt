package demo.nexa.plauid.domain.model

/**
 * Processing status of a recording note.
 */
enum class NoteStatus {
    NEW,           // Note created, processing not started
    TRANSCRIBING,  // ASR in progress
    SUMMARIZING,   // LLM summary generation in progress
    DONE,          // All processing complete
    ERROR          // Processing failed
}
