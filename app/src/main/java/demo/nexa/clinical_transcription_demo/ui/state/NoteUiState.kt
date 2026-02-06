package demo.nexa.clinical_transcription_demo.ui.state

data class NoteUiState(
    val id: String,
    val title: String,
    val date: String,
    val duration: String,
    val hasTranscript: Boolean = false,
    val isProcessing: Boolean = false
)
