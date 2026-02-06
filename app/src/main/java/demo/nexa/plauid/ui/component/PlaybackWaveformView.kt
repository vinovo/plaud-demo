package demo.nexa.plauid.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import demo.nexa.plauid.ui.theme.PlauColors

@Composable
fun PlaybackWaveformView(
    amplitudes: List<Float> = emptyList(), // Normalized amplitudes (0.0-1.0)
    currentPositionMs: Long = 0L,
    durationMs: Long = 1L,
    onScrubStart: () -> Unit = {},
    onScrubPreviewMs: (Long) -> Unit = {},
    onScrubEndMs: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Track scrubbing state locally for smooth UI rendering
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val currentPositionMsState by rememberUpdatedState(currentPositionMs)
    val durationMsState by rememberUpdatedState(durationMs)
    
    Card(
        modifier = modifier
            .height(180.dp)
            .pointerInput(Unit) { // Keep gesture alive; use updated state inside
                // Horizontal scroll/swipe to seek through audio
                var dragStartMs = 0L
                var dragTotalPx = 0f
                var lastTargetMs = 0L

                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStartMs = currentPositionMsState
                        dragTotalPx = 0f
                        lastTargetMs = currentPositionMsState
                        scrubPositionMs = currentPositionMsState
                        onScrubStart()
                    },
                    onDragEnd = {
                        onScrubEndMs(lastTargetMs)
                        scrubPositionMs = null
                    },
                    onDragCancel = {
                        // On cancel, still commit the position (don't lose the seek)
                        onScrubEndMs(lastTargetMs)
                        scrubPositionMs = null
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        
                        val effectiveDurationMs = durationMsState
                        if (effectiveDurationMs <= 0L) return@detectHorizontalDragGestures
                        
                        // Accumulate drag distance
                        dragTotalPx += dragAmount
                        
                        // Calculate and preview new position
                        val pixelsToMsRatio = effectiveDurationMs.toFloat() / size.width.toFloat()
                        val deltaMs = dragTotalPx * pixelsToMsRatio * 0.6f
                        
                        // Reverse direction: positive drag (right) = go backward in time
                        val targetMs = (dragStartMs - deltaMs.toLong())
                            .coerceIn(0L, effectiveDurationMs)
                        lastTargetMs = targetMs
                        scrubPositionMs = targetMs
                        
                        onScrubPreviewMs(targetMs)
                    }
                )
            },
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
            // Waveform bars with fixed center playhead and scrolling waveform
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                if (amplitudes.isEmpty()) return@Canvas
                
                val barWidth = 2.dp.toPx()
                val spacing = 1.5.dp.toPx()
                val centerY = size.height / 2
                val totalWidth = size.width
                val barCount = (totalWidth / (barWidth + spacing)).toInt()
                val dpToPx = 1.dp.toPx()
                val cornerRadius = androidx.compose.ui.geometry.CornerRadius(dpToPx)
                val maxBarHeight = size.height * 0.8f
                
                // Fixed playhead at center
                val playheadX = totalWidth / 2
                
                // Calculate progress (0.0 to 1.0) using display position
                val renderPositionMs = scrubPositionMs ?: currentPositionMs
                val progress = if (durationMs > 0) {
                    (renderPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                
                // Current position in waveform data (which sample we're at)
                val currentSampleIndex = (progress * amplitudes.size).toInt()
                    .coerceIn(0, amplitudes.size)
                
                // Calculate which bars to show
                // At start: all bars to the right of playhead (unplayed)
                // As playing: waveform scrolls left, played portion on left, unplayed on right
                val halfBarCount = barCount / 2
                
                // Draw bars
                for (i in 0 until barCount) {
                    val x = i * (barWidth + spacing)
                    
                    // Map bar position to waveform sample
                    // Bars to left of playhead: earlier samples (already played)
                    // Bars to right of playhead: later samples (not yet played)
                    val barOffsetFromCenter = i - halfBarCount
                    val sampleIndex = currentSampleIndex + barOffsetFromCenter
                    
                    if (sampleIndex >= 0 && sampleIndex < amplitudes.size) {
                        val amplitude = amplitudes[sampleIndex].coerceIn(0f, 1f)
                        val barHeight = maxOf(2.dp.toPx(), amplitude * maxBarHeight)
                        
                        // Color based on position relative to playhead
                        // Bars left of playhead (already played): dark
                        // Bars right of playhead (not yet played): light
                        val color = if (x < playheadX) {
                            PlauColors.TealDark // Played portion
                        } else {
                            PlauColors.TealLight.copy(alpha = 0.5f) // Unplayed portion
                        }
                        
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, centerY - barHeight / 2),
                            size = Size(barWidth, barHeight),
                            cornerRadius = cornerRadius
                        )
                    } else {
                        // Empty bar for padding (before start or after end)
                        val barHeight = 2.dp.toPx()
                        drawRoundRect(
                            color = PlauColors.TealLight.copy(alpha = 0.2f),
                            topLeft = Offset(x, centerY - barHeight / 2),
                            size = Size(barWidth, barHeight),
                            cornerRadius = cornerRadius
                        )
                    }
                }
                
                // Draw fixed playhead indicator at center
                // Vertical line
                drawRoundRect(
                    color = PlauColors.TealPrimary,
                    topLeft = Offset(playheadX - 1.dp.toPx(), 20.dp.toPx()),
                    size = Size(2.dp.toPx(), size.height - 40.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(0f)
                )
                
                // Triangle at top (pointing down)
                val trianglePath = Path().apply {
                    moveTo(playheadX, 18.dp.toPx())
                    lineTo(playheadX - 6.dp.toPx(), 10.dp.toPx())
                    lineTo(playheadX + 6.dp.toPx(), 10.dp.toPx())
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
private fun PlaybackWaveformViewPreview() {
    val sampleAmplitudes = List(200) { kotlin.random.Random.nextFloat() }
    
    Box(
        modifier = Modifier
            .padding(16.dp)
    ) {
        PlaybackWaveformView(
            amplitudes = sampleAmplitudes,
            currentPositionMs = 15000L,
            durationMs = 60000L
        )
    }
}
