package demo.nexa.clinical_transcription_demo.audio

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
            release()
            
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
    
    fun play() {
        if (isPrepared) {
            mediaPlayer?.start()
        }
    }
    
    fun pause() {
        if (isPrepared && mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
        }
    }
    
    fun togglePlayPause() {
        if (isPlaying()) {
            pause()
        } else {
            play()
        }
    }
    
    fun seekTo(positionMs: Long) {
        if (isPrepared) {
            mediaPlayer?.seekTo(positionMs.toInt())
        }
    }
    
    fun getCurrentPosition(): Long {
        return if (isPrepared) {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } else {
            0L
        }
    }
    
    fun getDuration(): Long {
        return if (isPrepared) {
            mediaPlayer?.duration?.toLong() ?: 0L
        } else {
            0L
        }
    }
    
    fun isPlaying(): Boolean {
        return isPrepared && (mediaPlayer?.isPlaying == true)
    }
    
    fun setOnCompletionListener(listener: () -> Unit) {
        mediaPlayer?.setOnCompletionListener {
            listener()
        }
    }
    
    fun release() {
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore
        } finally {
            mediaPlayer = null
            isPrepared = false
        }
    }
}
