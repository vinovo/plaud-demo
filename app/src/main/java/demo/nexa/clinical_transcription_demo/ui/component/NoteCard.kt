package demo.nexa.clinical_transcription_demo.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import demo.nexa.clinical_transcription_demo.R
import demo.nexa.clinical_transcription_demo.ui.state.NoteUiState
import demo.nexa.clinical_transcription_demo.ui.theme.PlauColors
import demo.nexa.clinical_transcription_demo.ui.theme.PlauDimens

@Composable
fun NoteCard(
    note: NoteUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(PlauDimens.cornerRadiusMedium),
        colors = CardDefaults.cardColors(
            containerColor = PlauColors.SurfaceWhite
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = PlauDimens.spacingSmall),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = note.title,
                    fontSize = PlauDimens.textSizeBodyLarge,
                    fontWeight = FontWeight.Normal,
                    color = PlauColors.TealDark,
                    letterSpacing = 0.25.sp
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PlauDimens.spacingXSmall),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = note.date,
                        fontSize = PlauDimens.textSizeCaption,
                        color = PlauColors.TextTertiary,
                        letterSpacing = 0.5.sp
                    )
                    
                    if (note.isProcessing) {
                        Row(
                            modifier = Modifier
                                .height(16.dp)
                                .background(
                                    color = Color(0xFFEDF2F0),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.card_loader),
                                contentDescription = "Processing",
                                modifier = Modifier.size(12.dp),
                                tint = Color.Unspecified
                            )
                            Text(
                                text = "Processing locally...",
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                color = Color(0xFF1F1F1F),
                                letterSpacing = 0.5.sp
                            )
                        }
                    } else if (note.hasTranscript) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = PlauColors.BadgeBackground,
                                    shape = RoundedCornerShape(PlauDimens.cornerRadiusMedium)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.message_square_quote),
                                contentDescription = "Has transcript",
                                modifier = Modifier.size(PlauDimens.iconSizeSmall),
                                tint = Color.Unspecified
                            )
                        }
                    }
                }
            }
            
            Text(
                text = note.duration,
                fontSize = PlauDimens.textSizeCaption,
                color = PlauColors.TextTertiary,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Preview
@Composable
private fun NoteCardPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier
                .background(PlauColors.BackgroundAqua)
                .padding(PlauDimens.spacingMedium),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NoteCard(
                note = NoteUiState(
                    id = "1",
                    title = "MRN-12345-THER-0003",
                    date = "Jan 30, 2026",
                    duration = "00:00:20",
                    hasTranscript = false,
                    isProcessing = true
                ),
                onClick = {}
            )
            NoteCard(
                note = NoteUiState(
                    id = "2",
                    title = "MRN-12345-THER-0002",
                    date = "Jun 16, 2025",
                    duration = "00:00:20",
                    hasTranscript = true,
                    isProcessing = false
                ),
                onClick = {}
            )
        }
    }
}
