package net.mildhope.nxtes.audio

import android.media.MediaPlayer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.mildhope.nxtes.data.Recording
import java.io.File

/** Plays one recording at a time, at 0.5x to 2x speed. */
@Stable
class AudioPlayer {
    /** The document and recording loaded, or null when the player is closed. */
    var loaded by mutableStateOf<Pair<String, Recording>?>(null)
        private set
    var playing by mutableStateOf(false)
        private set

    /** Milliseconds into the audio. */
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    private var media: MediaPlayer? = null

    fun load(documentId: String, recording: Recording, file: File) {
        close()
        val player = MediaPlayer()
        try {
            player.setDataSource(file.path)
            player.prepare()
        } catch (e: Exception) {
            player.release()
            throw e
        }
        player.setOnCompletionListener {
            playing = false
            position = duration
        }
        media = player
        duration = player.duration.toLong().takeIf { it > 0 } ?: recording.duration
        loaded = documentId to recording
        position = 0
    }

    fun play() {
        val player = media ?: return
        if (position >= duration - END_SLACK_MS) seek(0)
        player.start()
        player.playbackParams = player.playbackParams.setSpeed(speed)
        playing = true
    }

    fun pause() {
        media?.pause()
        playing = false
        tick()
    }

    fun toggle() = if (playing) pause() else play()

    fun seek(ms: Long) {
        val target = ms.coerceIn(0, duration)
        media?.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        position = target
    }

    fun updateSpeed(value: Float) {
        speed = value
        val player = media ?: return
        if (playing) player.playbackParams = player.playbackParams.setSpeed(value)
    }

    /** Reads the position from the player; called every frame while playing. */
    fun tick() {
        media?.let { position = it.currentPosition.toLong() }
    }

    fun close() {
        media?.release()
        media = null
        loaded = null
        playing = false
        position = 0
        duration = 0
    }

    companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private const val END_SLACK_MS = 100L
    }
}
