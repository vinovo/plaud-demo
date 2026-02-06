package demo.nexa.plauid.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility functions for time and date formatting.
 */

/**
 * Format elapsed time in milliseconds to "MM:SS" or "HH:MM:SS" format.
 *
 * @param ms Time in milliseconds
 * @param forceHours Force HH:MM:SS format even if hours is 0
 * @return Formatted time string (e.g., "01:23" or "01:23:45")
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

/**
 * Format a date as a human-readable timestamp for display.
 * Example: "Feb 3, 2026 14:30"
 *
 * @param date The date to format
 * @return Formatted date string
 */
fun formatDateForDisplay(date: Date): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    return dateFormat.format(date)
}

/**
 * Format current date as a compact filename-safe timestamp.
 * Example: "2026-02-03-14-30"
 *
 * @return Formatted timestamp string
 */
fun formatDateForFilename(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US)
    return dateFormat.format(Date())
}

/**
 * Format duration in milliseconds as a human-readable duration string.
 * Examples: "5 min", "1 hr 23 min", "45 sec"
 *
 * @param ms Duration in milliseconds
 * @return Formatted duration string
 */
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
