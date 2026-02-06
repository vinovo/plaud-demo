package demo.nexa.clinical_transcription_demo.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import demo.nexa.clinical_transcription_demo.common.formatElapsedTime
import demo.nexa.clinical_transcription_demo.presentation.RecordingViewModel
import demo.nexa.clinical_transcription_demo.ui.component.LoadingOverlay
import demo.nexa.clinical_transcription_demo.ui.component.WaveformView
import demo.nexa.clinical_transcription_demo.ui.theme.PlauColors
import demo.nexa.clinical_transcription_demo.ui.theme.PlauDimens

@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel,
    onBackClick: () -> Unit,
    onRecordingSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues()
    val statusBarsPadding = WindowInsets.statusBars.asPaddingValues()
    
    val uiState by viewModel.uiState.collectAsState()
    
    var permissionGranted by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionGranted = true
            viewModel.startRecording()
        } else {
            onBackClick()
        }
    }
    
    LaunchedEffect(Unit) {
        val permission = Manifest.permission.RECORD_AUDIO
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (hasPermission) {
            permissionGranted = true
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(permission)
        }
    }
    
    LaunchedEffect(uiState.recordingSaved) {
        if (uiState.recordingSaved) {
            onRecordingSaved()
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            if (uiState.isRecording) {
                viewModel.discardRecording()
            }
        }
    }
    
    val handleBackPress: () -> Unit = {
        if (uiState.isRecording) {
            showDiscardDialog = true
        } else {
            onBackClick()
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PlauColors.BackgroundTeal)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .padding(
                        start = PlauDimens.spacingMedium,
                        top = statusBarsPadding.calculateTopPadding() + PlauDimens.spacingSmall,
                        bottom = PlauDimens.spacingSmall
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = handleBackPress,
                    modifier = Modifier.size(PlauDimens.iconSizeDefault)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = PlauColors.IconGray
                    )
                }
                Text(
                    text = "Record",
                    fontSize = PlauDimens.textSizeTitle,
                    fontWeight = FontWeight.Medium,
                    color = PlauColors.TextPrimary,
                    modifier = Modifier.padding(start = PlauDimens.spacingMedium)
                )
            }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PlauDimens.spacingMedium, vertical = PlauDimens.spacingSmall),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                WaveformView(amplitudes = uiState.waveformAmplitudes)
                
                Text(
                    text = formatElapsedTime(uiState.elapsedTimeMs),
                    fontSize = PlauDimens.textSizeLarge,
                    fontWeight = FontWeight.Normal,
                    color = PlauColors.TealDark,
                    modifier = Modifier.padding(top = PlauDimens.spacingLarge)
                )
            }
        }
        
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navigationBarsPadding.calculateBottomPadding() + PlauDimens.spacingLarge)
                .size(PlauDimens.fabSizeLarge)
                .background(PlauColors.SurfaceWhite, CircleShape)
                .border(PlauDimens.borderWidthThin, PlauColors.BorderMedium, CircleShape)
                .clickable(
                    onClick = { viewModel.stopRecording() },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(PlauDimens.iconSizeDefault)
                    .background(
                        color = PlauColors.AccentRed,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
        
        if (uiState.isProcessing) {
            LoadingOverlay(message = "Processing recording...")
        }
    }
    
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard Recording?") },
            text = { Text("Are you sure you want to discard this recording? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { 
                    showDiscardDialog = false
                    viewModel.discardRecording()
                    onBackClick()
                }) {
                    Text("Discard", color = PlauColors.AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    uiState.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Recording Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.dismissError()
                    onBackClick()
                }) {
                    Text("OK")
                }
            }
        )
    }
}
