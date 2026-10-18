package net.mildhope.nxtes.audio

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.mildhope.nxtes.data.AudioSpan
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.data.Recording
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A recording under way for a document: its finished spans, and when the running one began. */
class RecordingSession(val documentId: String, val spans: List<AudioSpan>, val runningSince: Long?, private val runningElapsed: Long) {
    val paused get() = runningSince == null

    /** Milliseconds recorded so far. */
    fun elapsed(): Long = spans.sumOf { it.length } + if (runningSince == null) 0 else SystemClock.elapsedRealtime() - runningElapsed
}

/**
 * Records the microphone for one document at a time, kept alive in the background by
 * [RecordingService]. Spans are measured on the same clock as strokes, so writing done while
 * recording can be found in the audio.
 */
@Stable
class AudioRecorder(private val context: Context, private val library: LibraryStore, private val scope: CoroutineScope) {
    var session by mutableStateOf<RecordingSession?>(null)
        private set

    /** The last recording that finished, until the screen showing it takes it. */
    var finished by mutableStateOf<Pair<String, Recording>?>(null)

    private var recorder: MediaRecorder? = null
    private val file get() = File(context.filesDir, "recording.m4a")

    fun start(documentId: String) {
        if (session != null) return
        file.delete()
        @Suppress("DEPRECATION")
        val media = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(SAMPLE_RATE)
            setAudioEncodingBitRate(BIT_RATE)
            setMaxDuration(MAX_DURATION_MS)
            setOutputFile(file)
            setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop() }
        }
        try {
            media.prepare()
            media.start()
        } catch (e: Exception) {
            media.release()
            throw e
        }
        recorder = media
        session = RecordingSession(documentId, emptyList(), System.currentTimeMillis(), SystemClock.elapsedRealtime())
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
    }

    fun pause() {
        val s = session ?: return
        if (s.paused) return
        recorder?.pause()
        session = RecordingSession(s.documentId, s.spans + currentSpan(s), null, 0)
    }

    fun resume() {
        val s = session ?: return
        if (!s.paused) return
        recorder?.resume()
        session = RecordingSession(s.documentId, s.spans, System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }

    /** Stops and saves the recording into its document. */
    fun stop() {
        val s = session ?: return
        val spans = if (s.paused) s.spans else s.spans + currentSpan(s)
        session = null
        val stopped = runCatching { recorder?.stop() }.isSuccess
        recorder?.release()
        recorder = null
        context.stopService(Intent(context, RecordingService::class.java))
        if (!stopped || spans.sumOf { it.length } <= 0) {
            file.delete()
            return
        }
        val title = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(spans.first().start))
        scope.launch(Dispatchers.IO) {
            runCatching { library.addRecording(s.documentId, file, title, spans) }
                .onSuccess { finished = s.documentId to it }
                .onFailure { Log.w("Recorder", "Saving the recording failed", it) }
        }
    }

    private fun currentSpan(s: RecordingSession): AudioSpan {
        val start = s.runningSince ?: return AudioSpan(0, 0)
        return AudioSpan(start, s.elapsed() - s.spans.sumOf { it.length })
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 64_000
        const val MAX_DURATION_MS = 6 * 60 * 60 * 1000
    }
}
