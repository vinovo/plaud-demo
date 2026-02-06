package demo.nexa.plauid

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
import demo.nexa.plauid.data.mapper.toUiState
import demo.nexa.plauid.data.repository.NotesRepository
import demo.nexa.plauid.presentation.MainViewModel
import demo.nexa.plauid.presentation.RecordingViewModel
import demo.nexa.plauid.ui.component.LoadingOverlay
import demo.nexa.plauid.ui.screen.NoteDetailScreen
import demo.nexa.plauid.ui.screen.NotesListScreen
import demo.nexa.plauid.ui.screen.RecordingScreen
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
                    var selectedNoteId by remember { mutableStateOf<String?>(null) }
                    val mainViewModel: MainViewModel = viewModel()
                    
                    // Observe notes from database
                    val notes by repository.observeAllNotes()
                        .map { domainNotes -> domainNotes.map { it.toUiState() } }
                        .collectAsState(initial = emptyList())
                    
                    // Observe importing state
                    val isImporting by mainViewModel.isImporting.collectAsState()
                    
                    // Audio file picker for import
                    val importLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        uri?.let { mainViewModel.importAudio(it) }
                    }
                    
                    // Handle UI events from ViewModel
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
                        // Main content
                        when {
                            showRecording -> {
                                val recordingViewModel: RecordingViewModel = viewModel()
                                RecordingScreen(
                                    viewModel = recordingViewModel,
                                    onBackClick = { showRecording = false },
                                    onRecordingSaved = { 
                                        showRecording = false
                                        // Notes list will auto-update via Flow
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
                                    onRecordClick = { showRecording = true },
                                    onImportClick = { 
                                        // Launch file picker for audio files
                                        importLauncher.launch("audio/*")
                                    },
                                    onTestAsrClick = {
                                        // TEMPORARY: Launch ASR test activity
                                        startActivity(Intent(this@MainActivity, TestAsrActivity::class.java))
                                    },
                                    onTestLlmClick = {
                                        // TEMPORARY: Launch LLM test activity
                                        startActivity(Intent(this@MainActivity, TestLlmActivity::class.java))
                                    }
                                )
                            }
                        }
                        
                        // Loading overlay when importing
                        if (isImporting) {
                            LoadingOverlay(message = "Importing audio...")
                        }
                    }
                }
            }
        }
    }
}