package demo.nexa.plauid.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Centralized gradient definitions for the Plauid app.
 */
object PlauGradients {
    // The primary gradient colors used throughout the app
    val primaryColors = listOf(
        Color(0xFF75CAB9),
        Color(0xFF6FAFD3),
        Color(0xFF738ED7),
        Color(0xFF7F7AD1)
    )
    
    // Pre-built gradient brushes for common use cases
    val horizontalGradient = Brush.horizontalGradient(colors = primaryColors)
    val linearGradient = Brush.linearGradient(colors = primaryColors)
}
