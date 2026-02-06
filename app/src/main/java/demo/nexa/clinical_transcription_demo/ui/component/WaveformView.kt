package demo.nexa.clinical_transcription_demo.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import demo.nexa.clinical_transcription_demo.ui.theme.PlauColors

// Sample waveform heights (simulating audio data)
private val sampleHeights = listOf(
    6f, 6f, 62f, 6f, 6f, 6f, 138f, 138f, 112f, 75f, 
    150f, 100f, 6f, 6f, 6f, 62f, 38f, 12f, 25f, 38f,
    75f, 112f, 100f, 112f, 125f, 100f, 88f, 38f, 38f, 50f,
    62f, 38f, 25f, 25f, 38f, 25f, 6f, 4f
)

@Composable
fun WaveformView(
    amplitudes: List<Float> = emptyList(),
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(328.dp)
            .height(368.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = PlauColors.SurfaceWhite
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val barWidth = 2.dp.toPx()
                val spacing = 1.5.dp.toPx()
                val centerY = size.height / 2
                val totalWidth = size.width
                val barCount = (totalWidth / (barWidth + spacing)).toInt()
                val dpToPx = 1.dp.toPx()
                val cornerRadius = androidx.compose.ui.geometry.CornerRadius(dpToPx)
                val maxBarHeight = size.height * 0.8f
                
                val dataToUse = if (amplitudes.isNotEmpty()) {
                    amplitudes
                } else {
                    sampleHeights.map { it / 150f }
                }
                
                val playheadBarIndex = barCount / 2
                
                for (i in 0 until barCount) {
                    val x = i * (barWidth + spacing)
                    val barOffsetFromPlayhead = i - playheadBarIndex
                    
                    val dataIndex = if (dataToUse.size > 0) {
                        val indexFromEnd = dataToUse.size - 1 + barOffsetFromPlayhead
                        if (indexFromEnd >= 0 && indexFromEnd < dataToUse.size) {
                            indexFromEnd
                        } else {
                            -1
                        }
                    } else {
                        -1
                    }
                    
                    val amplitude = if (dataIndex >= 0 && dataIndex < dataToUse.size) {
                        dataToUse[dataIndex].coerceIn(0f, 1f)
                    } else {
                        0.02f // Minimal baseline
                    }
                    
                    // Scale amplitude to bar height (minimum 2dp for visibility)
                    val barHeight = maxOf(2.dp.toPx(), amplitude * maxBarHeight)
                    
                    // Color based on whether we have data
                    val color = if (dataIndex >= 0 && amplitude > 0.02f) {
                        PlauColors.TealPrimary // Teal for recorded
                    } else {
                        PlauColors.SurfaceWhite.copy(alpha = 0.3f) // Faint for empty
                    }
                    
                    // Draw bar centered vertically
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, centerY - barHeight / 2),
                        size = Size(barWidth, barHeight),
                        cornerRadius = cornerRadius
                    )
                }
            }
            
            // Playhead indicator (vertical line with triangle)
            Canvas(
                modifier = Modifier
                    .width(12.dp)
                    .height(348.dp)
            ) {
                val centerX = size.width / 2 + 2.dp.toPx()
                
                // Vertical line
                drawRoundRect(
                    color = PlauColors.TealLight,
                    topLeft = Offset(centerX - 1.dp.toPx(), 20.dp.toPx()),
                    size = Size(2.dp.toPx(), 338.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(0f)
                )
                
                // Triangle at top (pointing down)
                val trianglePath = Path().apply {
                    moveTo(centerX, 18.dp.toPx()) // Bottom point
                    lineTo(centerX - 6.dp.toPx(), 10.dp.toPx()) // Top left
                    lineTo(centerX + 6.dp.toPx(), 10.dp.toPx()) // Top right
                    close()
                }
                drawPath(
                    path = trianglePath,
                    color = PlauColors.TealDark,
                    style = Fill
                )
            }
        }
    }
}

@Preview
@Composable
private fun WaveformViewPreview() {
    Box(
        modifier = Modifier
            .background(PlauColors.BackgroundTeal)
            .padding(16.dp)
    ) {
        WaveformView()
    }
}
