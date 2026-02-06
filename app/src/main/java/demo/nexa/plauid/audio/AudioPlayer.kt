package demo.nexa.plauid.audio

import android.media.MediaPlayer
import java.io.File

/**
 * Wrapper around MediaPlayer for audio playback.
 * Provides simplified interface for play/pause/seek operations.
 */
class AudioPlayer {
    
    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    
    /**
     * Prepare the player with an audio file.
     * Must be called before play/pause/seek operations.
     */
    fun prepare(audioFile: File): Result<Unit> {
        return try {
            release() // Release any existing player
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
            }
            isPrepared = true
            
            Result.success(Unit)
        } catch (e: Exception) {
            isPrepared = false
            Result.failure(e)
        }
    }
    
    /**
     * Start or resume playback.
     */
    fun play() {
        if (isPrepared) {
            mediaPlayer?.start()
        }
    }
    
    /**
     * Pause playback.
     */
    fun pause() {
        if (isPrepared && mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
        }
    }
    
    /**
     * Toggle play/pause.
     */
    fun togglePlayPause() {
        if (isPlaying()) {
            pause()
        } else {
            play()
        }
    }
    
    /**
     * Seek to a specific position in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        if (isPrepared) {
            mediaPlayer?.seekTo(positionMs.toInt())
        }
    }
    
    /**
     * Get current playback position in milliseconds.
     */
    fun getCurrentPosition(): Long {
        return if (isPrepared) {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } else {
            0L
        }
    }
    
    /**
     * Get total duration in milliseconds.
     */
    fun getDuration(): Long {
        return if (isPrepared) {
            mediaPlayer?.duration?.toLong() ?: 0L
        } else {
            0L
        }
    }
    
    /**
     * Check if audio is currently playing.
     */
    fun isPlaying(): Boolean {
        return isPrepared && (mediaPlayer?.isPlaying == true)
    }
    
    /**
     * Set a listener for when playback completes.
     */
    fun setOnCompletionListener(listener: () -> Unit) {
        mediaPlayer?.setOnCompletionListener {
            listener()
        }
    }
    
    /**
     * Release the player and free resources.
     */
    fun release() {
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore errors during cleanup
        } finally {
            mediaPlayer = null
            isPrepared = false
        }
    }
}
