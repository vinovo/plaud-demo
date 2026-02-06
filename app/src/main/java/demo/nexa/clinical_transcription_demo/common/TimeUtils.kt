package demo.nexa.clinical_transcription_demo.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility functions for time and date formatting.
 */

fun formatElapsedTime(ms: Long, forceHours: Boolean = false): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0 || forceHours) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatDateForDisplay(date: Date): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    return dateFormat.format(date)
}

fun formatDateForFilename(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US)
    return dateFormat.format(Date())
}

fun formatDurationForDisplay(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return when {
        hours > 0 && minutes > 0 -> "$hours hr $minutes min"
        hours > 0 -> "$hours hr"
        minutes > 0 -> "$minutes min"
        else -> "$seconds sec"
    }
}
