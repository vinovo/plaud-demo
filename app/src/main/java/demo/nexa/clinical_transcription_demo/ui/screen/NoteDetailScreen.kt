package demo.nexa.clinical_transcription_demo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import demo.nexa.clinical_transcription_demo.R
import demo.nexa.clinical_transcription_demo.common.formatDateForDisplay
import demo.nexa.clinical_transcription_demo.common.formatDurationForDisplay
import demo.nexa.clinical_transcription_demo.common.formatElapsedTime
import demo.nexa.clinical_transcription_demo.domain.model.NoteStatus
import demo.nexa.clinical_transcription_demo.presentation.NotePlaybackViewModel
import demo.nexa.clinical_transcription_demo.presentation.PlaybackUiState
import demo.nexa.clinical_transcription_demo.ui.component.GradientOutlineStatusRow
import demo.nexa.clinical_transcription_demo.ui.component.GradientPillButton
import demo.nexa.clinical_transcription_demo.ui.component.PlaybackWaveformView
import demo.nexa.clinical_transcription_demo.ui.theme.PlauColors
import demo.nexa.clinical_transcription_demo.ui.theme.PlauDimens
import demo.nexa.clinical_transcription_demo.ui.theme.PlauGradients
import java.util.Date

@Composable
fun NoteDetailScreen(
    noteId: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotePlaybackViewModel = viewModel()
) {
    LaunchedEffect(noteId) {
        viewModel.loadNote(noteId)
    }
    
    val uiState by viewModel.uiState.collectAsState()
    
    when (val state = uiState) {
        is PlaybackUiState.Loading -> {
            LoadingScreen(onBackClick = onBackClick, modifier = modifier)
        }
        is PlaybackUiState.Error -> {
            ErrorScreen(
                message = state.message,
                onBackClick = onBackClick,
                modifier = modifier
            )
        }
        is PlaybackUiState.Ready -> {
            NoteDetailContent(
                state = state,
                onBackClick = onBackClick,
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onScrubStart = { viewModel.beginScrub() },
                onScrubPreviewMs = { viewModel.previewScrub(it) },
                onScrubEndMs = { viewModel.endScrub(it) },
                onGenerateSummary = { viewModel.generateSummary() },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun LoadingScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PlauColors.BackgroundTeal),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.loading),
            color = PlauColors.TextPrimary,
            fontSize = PlauDimens.textSizeBody
        )
    }
}

@Composable
private fun ErrorScreen(
    message: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBarsPadding = WindowInsets.statusBars.asPaddingValues()
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PlauColors.BackgroundTeal)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar with back button
            Row(
                modifier = Modifier.padding(
                    start = PlauDimens.spacingMedium,
                    top = statusBarsPadding.calculateTopPadding() + PlauDimens.spacingSmall
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(PlauDimens.iconSizeDefault)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = PlauColors.IconGray
                    )
                }
            }
            
            // Error message
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message,
                    color = PlauColors.TextPrimary,
                    fontSize = PlauDimens.textSizeBody
                )
            }
        }
    }
}

@Composable
private fun NoteDetailContent(
    state: PlaybackUiState.Ready,
    onBackClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubPreviewMs: (Long) -> Unit,
    onScrubEndMs: (Long) -> Unit,
    onGenerateSummary: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Format date and time
    val date = Date(state.note.createdAtEpochMs)
    val createdDate = formatDateForDisplay(date)
    
    // Format duration and current time consistently
    // Always show hours for duration to match the "00:00:00" start timestamp in transcription
    val duration = formatElapsedTime(state.durationMs, forceHours = true)
    val durationHasHours = state.durationMs >= 3600000 // 1 hour in ms
    val currentTime = formatElapsedTime(state.currentPositionMs, forceHours = durationHasHours)
    val statusBarsPadding = WindowInsets.statusBars.asPaddingValues()
    val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PlauColors.BackgroundTeal)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top navigation bar
            Column(
                modifier = Modifier
                    .padding(
                        start = PlauDimens.spacingMedium,
                        top = statusBarsPadding.calculateTopPadding() + PlauDimens.spacingSmall,
                        bottom = PlauDimens.spacingSmall
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(PlauDimens.iconSizeDefault)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = PlauColors.IconGray
                        )
                    }
                    Column(
                        modifier = Modifier.padding(start = PlauDimens.spacingMedium),
                        verticalArrangement = Arrangement.spacedBy(-2.dp)
                    ) {
                        Text(
                            text = state.note.title,
                            fontSize = PlauDimens.textSizeTitle,
                            fontWeight = FontWeight.Medium,
                            color = PlauColors.TextPrimary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(PlauDimens.spacingSmall)
                        ) {
                            Text(
                                text = createdDate,
                                fontSize = PlauDimens.textSizeCaption,
                                fontWeight = FontWeight.Medium,
                                color = PlauColors.TextSecondary,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = duration,
                                fontSize = PlauDimens.textSizeCaption,
                                fontWeight = FontWeight.Normal,
                                color = PlauColors.TextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
            
            // Waveform section
            Column(
                modifier = Modifier.padding(horizontal = PlauDimens.spacingMedium, vertical = PlauDimens.spacingSmall),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PlaybackWaveformView(
                    amplitudes = state.waveformAmplitudes,
                    currentPositionMs = state.currentPositionMs,
                    durationMs = state.durationMs,
                    onScrubStart = onScrubStart,
                    onScrubPreviewMs = onScrubPreviewMs,
                    onScrubEndMs = onScrubEndMs,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Timer and play button
                Box(
                    modifier = Modifier
                        .width(328.dp)
                        .height(PlauDimens.pillHeight)
                        .background(
                            color = PlauColors.ProgressOverlay,
                            shape = RoundedCornerShape(PlauDimens.cornerRadiusCircle)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currentTime,
                        fontSize = PlauDimens.textSizeTitle,
                        fontWeight = FontWeight.Medium,
                        color = PlauColors.TealDark
                    )
                    
                    // Play/Pause button on the right
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(PlauDimens.pillHeight)
                            .border(
                                width = PlauDimens.borderWidthThin,
                                brush = PlauGradients.linearGradient,
                                shape = CircleShape
                            )
                            .background(PlauColors.SurfaceWhite, CircleShape)
                            .clickable(
                                onClick = onPlayPauseClick,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                if (state.isPlaying) android.R.drawable.ic_media_pause 
                                else R.drawable.play
                            ),
                            contentDescription = stringResource(
                                if (state.isPlaying) R.string.pause else R.string.play
                            ),
                            modifier = Modifier.size(20.dp),
                            tint = if (state.isPlaying) PlauColors.TealDark else Color.Unspecified
                        )
                    }
                }
            }
            
            // Bottom tabs section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = PlauColors.SurfaceWhite
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 2.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PlauDimens.spacingMedium)
                ) {
                    // Custom tabs
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PlauDimens.pillHeight)
                            .background(
                                color = PlauColors.TabBackground,
                                shape = RoundedCornerShape(PlauDimens.cornerRadiusMedium)
                            )
                            .padding(PlauDimens.spacingXSmall)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Transcription tab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(
                                        color = if (selectedTabIndex == 0) PlauColors.SurfaceWhite else Color.Transparent,
                                        shape = RoundedCornerShape(PlauDimens.cornerRadiusSmall)
                                    )
                                    .clickable { selectedTabIndex = 0 },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.transcription_tab),
                                    fontSize = PlauDimens.textSizeBody,
                                    fontWeight = FontWeight.Medium,
                                    color = PlauColors.TealDark,
                                    letterSpacing = 0.1.sp
                                )
                            }
                            
                            // Summary tab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(
                                        color = if (selectedTabIndex == 1) PlauColors.SurfaceWhite else Color.Transparent,
                                        shape = RoundedCornerShape(PlauDimens.cornerRadiusSmall)
                                    )
                                    .clickable { selectedTabIndex = 1 },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.summary_tab),
                                    fontSize = PlauDimens.textSizeBody,
                                    fontWeight = FontWeight.Medium,
                                    color = PlauColors.TealDark,
                                    letterSpacing = 0.1.sp
                                )
                            }
                        }
                    }
                    
                    // Tab content
                    when (selectedTabIndex) {
                        0 -> {
                            TranscriptionTabContent(
                                note = state.note,
                                transcriptionProgress = state.transcriptionProgress,
                                duration = duration,
                                navigationBarsPadding = navigationBarsPadding
                            )
                        }
                        1 -> {
                            SummaryTabContent(
                                summaryText = state.note.summaryText,
                                isGeneratingSummary = state.isGeneratingSummary,
                                summaryProgress = state.summaryProgress,
                                summaryError = state.summaryError,
                                durationMs = state.durationMs,
                                onGenerateSummaryClick = onGenerateSummary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Timestamp indicator icon with two concentric circles
 */
@Composable
private fun TimestampDotIcon(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(PlauDimens.timestampDotOuter),
        contentAlignment = Alignment.Center
    ) {
        // Outer circle
        Box(
            modifier = Modifier
                .size(PlauDimens.timestampDotOuter)
                .background(
                    color = PlauColors.TealLight,
                    shape = CircleShape
                )
        )
        // Inner circle
        Box(
            modifier = Modifier
                .size(PlauDimens.timestampDotInner)
                .background(
                    color = PlauColors.TealPrimary.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        )
    }
}

/**
 * Displays a transcript segment with timestamp and text
 */
@Composable
private fun TranscriptSegmentView(
    timestampText: String,
    transcriptText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PlauDimens.spacingXSmall)
    ) {
        // Timestamp row with dot icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PlauDimens.spacingSmall),
            modifier = Modifier.padding(vertical = PlauDimens.spacingXSmall)
        ) {
            TimestampDotIcon()
            
            Text(
                text = timestampText,
                fontSize = PlauDimens.textSizeBody,
                fontWeight = FontWeight.Normal,
                color = PlauColors.TextTertiary,
                lineHeight = PlauDimens.lineHeightBody,
                letterSpacing = 0.15.sp
            )
        }
        
        // Transcript text
        Text(
            text = transcriptText,
            fontSize = PlauDimens.textSizeBodyLarge,
            fontWeight = FontWeight.Normal,
            color = PlauColors.TextPrimary,
            lineHeight = PlauDimens.lineHeightBodyLarge,
            letterSpacing = 0.25.sp
        )
    }
}

/**
 * Content for the Transcription tab
 */
@Composable
private fun TranscriptionTabContent(
    note: demo.nexa.clinical_transcription_demo.domain.model.RecordingNote,
    transcriptionProgress: Int?,
    duration: String,
    navigationBarsPadding: androidx.compose.foundation.layout.PaddingValues,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = PlauDimens.spacingSmall),
        contentAlignment = Alignment.Center
    ) {
        when {
            note.status == NoteStatus.TRANSCRIBING -> {
                val progressText = if (transcriptionProgress != null) {
                    stringResource(R.string.transcribing_progress, transcriptionProgress)
                } else {
                    stringResource(R.string.transcribing)
                }
                GradientOutlineStatusRow(
                    text = progressText,
                    iconRes = R.drawable.loader,
                    iconContentDescription = stringResource(R.string.transcribing)
                )
            }
            note.status == NoteStatus.ERROR && note.transcriptText == null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PlauDimens.spacingSmall)
                ) {
                    GradientOutlineStatusRow(
                        text = stringResource(R.string.transcription_failed),
                        iconRes = R.drawable.message_square_quote,
                        iconContentDescription = stringResource(R.string.transcription_failed)
                    )
                    note.errorMessage?.let { errorMsg ->
                        Text(
                            text = errorMsg,
                            fontSize = PlauDimens.textSizeCaption,
                            color = PlauColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = PlauDimens.spacingSmall),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            note.transcriptText != null -> {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(
                            start = PlauDimens.spacingSmall,
                            end = PlauDimens.spacingSmall,
                            bottom = navigationBarsPadding.calculateBottomPadding() + PlauDimens.spacingMedium
                        )
                ) {
                    TranscriptSegmentView(
                        timestampText = "00:00:00 - $duration",
                        transcriptText = note.transcriptText
                    )
                }
            }
            else -> {
                GradientOutlineStatusRow(
                    text = stringResource(R.string.no_transcript_yet),
                    iconRes = R.drawable.message_square_quote,
                    iconContentDescription = stringResource(R.string.no_transcript_yet)
                )
            }
        }
    }
}

/**
 * Content for the Summary tab
 */
@Composable
private fun SummaryTabContent(
    summaryText: String?,
    isGeneratingSummary: Boolean,
    summaryProgress: Int?,
    summaryError: String?,
    durationMs: Long,
    onGenerateSummaryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = PlauDimens.spacingSmall),
        contentAlignment = Alignment.Center
    ) {
        when {
            summaryText != null -> {
                // Show summary text in scrollable markdown container with session notes header
                val scrollState = rememberScrollState()
                var showContextMenu by remember { mutableStateOf(false) }
                var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
                val density = androidx.compose.ui.platform.LocalDensity.current
                
                // Build the full content text for copying
                val fullContentText = buildString {
                    appendLine("Session Notes:")
                    appendLine()
                    appendLine("⏱️ ${formatDurationForDisplay(durationMs)}")
                    appendLine("🔒 On-device processed")
                    appendLine()
                    appendLine("---")
                    appendLine()
                    append(summaryText)
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(PlauDimens.spacingSmall)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = PlauColors.SurfaceWhite,
                                shape = RoundedCornerShape(PlauDimens.cornerRadiusMedium)
                            )
                            .border(
                                width = 1.dp,
                                color = PlauColors.TabBackground,
                                shape = RoundedCornerShape(PlauDimens.cornerRadiusMedium)
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = { offset ->
                                        // Convert px to dp and store the press position
                                        pressOffset = with(density) {
                                            DpOffset(
                                                x = offset.x.toDp(),
                                                y = offset.y.toDp()
                                            )
                                        }
                                        showContextMenu = true
                                    }
                                )
                            }
                            .padding(PlauDimens.spacingMedium)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(PlauDimens.spacingMedium)
                        ) {
                            // Session Notes Header Section
                            Column(
                                verticalArrangement = Arrangement.spacedBy(PlauDimens.spacingXSmall)
                            ) {
                                Text(
                                    text = "Session Notes:",
                                    fontSize = PlauDimens.textSizeBodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PlauColors.TextPrimary
                                )
                                
                                // Duration line with clock emoji
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⏱️",
                                        fontSize = PlauDimens.textSizeBody
                                    )
                                    Text(
                                        text = formatDurationForDisplay(durationMs),
                                        fontSize = PlauDimens.textSizeBody,
                                        color = PlauColors.TextSecondary
                                    )
                                }
                                
                                // On-device processed line
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🔒",
                                        fontSize = PlauDimens.textSizeBody
                                    )
                                    Text(
                                        text = "On-device processed",
                                        fontSize = PlauDimens.textSizeBody,
                                        color = PlauColors.TextSecondary
                                    )
                                }
                            }
                            
                            // Horizontal divider
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(PlauColors.TabBackground)
                            )
                            
                            // LLM Output (Markdown)
                            Material3RichText(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Markdown(
                                    content = summaryText
                                )
                            }
                        }
                    }
                    
                    // Context Menu Dropdown
                    DropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false },
                        offset = pressOffset,
                        modifier = Modifier
                            .width(180.dp)
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(8.dp),
                                ambientColor = Color(0x0A1C4F54),
                                spotColor = Color(0x1F1C4F54)
                            )
                            .background(
                                color = Color(0xFFFCFCFC),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = Color(0xFFE7E7E7),
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        // Copy menu item
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Copy",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = Color(0xFF1F1F1F),
                                        letterSpacing = 0.25.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.ic_copy),
                                        contentDescription = "Copy",
                                        modifier = Modifier.size(24.dp),
                                        tint = Color(0xFF454545)
                                    )
                                }
                            },
                            onClick = {
                                // Copy content to clipboard
                                clipboardManager.setText(AnnotatedString(fullContentText))
                                showContextMenu = false
                            },
                            modifier = Modifier.height(48.dp)
                        )
                        
                        // Export menu item
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Export",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = Color(0xFF1F1F1F),
                                        letterSpacing = 0.25.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.ic_export),
                                        contentDescription = "Export",
                                        modifier = Modifier.size(24.dp),
                                        tint = Color(0xFF454545)
                                    )
                                }
                            },
                            onClick = {
                                // Show not supported message
                                Toast.makeText(context, "Export is not supported yet", Toast.LENGTH_SHORT).show()
                                showContextMenu = false
                            },
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
            }
            isGeneratingSummary -> {
                // Show generating status with progress
                val progressText = if (summaryProgress != null) {
                    "Generating Summary ($summaryProgress%)..."
                } else {
                    stringResource(R.string.generating_summary)
                }
                GradientOutlineStatusRow(
                    text = progressText,
                    iconRes = R.drawable.loader,
                    iconContentDescription = stringResource(R.string.generating_summary)
                )
            }
            summaryError != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PlauDimens.spacingSmall)
                ) {
                    GradientOutlineStatusRow(
                        text = stringResource(R.string.llm_generation_failed),
                        iconRes = R.drawable.message_square_quote,
                        iconContentDescription = stringResource(R.string.llm_generation_failed)
                    )
                    Text(
                        text = summaryError,
                        fontSize = PlauDimens.textSizeCaption,
                        color = PlauColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = PlauDimens.spacingSmall),
                        textAlign = TextAlign.Center
                    )
                    // Retry button
                    GradientPillButton(
                        text = stringResource(R.string.generate_summary),
                        iconRes = R.drawable.sparkles,
                        onClick = onGenerateSummaryClick,
                        iconContentDescription = stringResource(R.string.generate_summary)
                    )
                }
            }
            else -> {
                GradientPillButton(
                    text = stringResource(R.string.generate_summary),
                    iconRes = R.drawable.sparkles,
                    onClick = onGenerateSummaryClick,
                    iconContentDescription = stringResource(R.string.generate_summary)
                )
            }
        }
    }
}

// Preview removed - requires ViewModel
