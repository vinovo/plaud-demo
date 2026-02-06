package demo.nexa.clinical_transcription_demo

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import demo.nexa.clinical_transcription_demo.data.mapper.toUiState
import demo.nexa.clinical_transcription_demo.data.repository.NotesRepository
import demo.nexa.clinical_transcription_demo.presentation.MainViewModel
import demo.nexa.clinical_transcription_demo.presentation.RecordingViewModel
import demo.nexa.clinical_transcription_demo.ui.component.LoadingOverlay
import demo.nexa.clinical_transcription_demo.ui.screen.NoteDetailScreen
import demo.nexa.clinical_transcription_demo.ui.screen.NotesListScreen
import demo.nexa.clinical_transcription_demo.ui.screen.RecordingScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    
    private val repository by lazy { NotesRepository.getInstance(this) }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showRecording by remember { mutableStateOf(false) }
                    var recordingSessionKey by remember { mutableStateOf(0) }
                    var selectedNoteId by remember { mutableStateOf<String?>(null) }
                    val mainViewModel: MainViewModel = viewModel()
                    
                    val notes by repository.observeAllNotes()
                        .map { domainNotes -> domainNotes.map { it.toUiState() } }
                        .collectAsState(initial = emptyList())
                    
                    val isImporting by mainViewModel.isImporting.collectAsState()
                    
                    val importLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        uri?.let { mainViewModel.importAudio(it) }
                    }
                    
                    LaunchedEffect(Unit) {
                        mainViewModel.uiEvents.collectLatest { event ->
                            when (event) {
                                is MainViewModel.UiEvent.ShowToast -> {
                                    Toast.makeText(
                                        this@MainActivity,
                                        event.message,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            showRecording -> {
                                val recordingViewModel: RecordingViewModel = viewModel(key = recordingSessionKey.toString())
                                RecordingScreen(
                                    viewModel = recordingViewModel,
                                    onBackClick = { showRecording = false },
                                    onRecordingSaved = { 
                                        showRecording = false
                                    }
                                )
                            }
                            selectedNoteId != null -> {
                                NoteDetailScreen(
                                    noteId = selectedNoteId!!,
                                    onBackClick = { selectedNoteId = null }
                                )
                            }
                            else -> {
                                NotesListScreen(
                                    notes = notes,
                                    onNoteClick = { note -> selectedNoteId = note.id },
                                    onRecordClick = { 
                                        recordingSessionKey++
                                        showRecording = true 
                                    },
                                    onImportClick = { 
                                        importLauncher.launch("audio/*")
                                    },
                                    onTestAsrClick = {
                                        startActivity(Intent(this@MainActivity, TestAsrActivity::class.java))
                                    },
                                    onTestLlmClick = {
                                        startActivity(Intent(this@MainActivity, TestLlmActivity::class.java))
                                    }
                                )
                            }
                        }
                        
                        if (isImporting) {
                            LoadingOverlay(message = "Importing audio...")
                        }
                    }
                }
            }
        }
    }
}