package net.mildhope.nxtes.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.mildhope.nxtes.R
import net.mildhope.nxtes.data.Document
import net.mildhope.nxtes.data.Recording
import java.util.Locale

val RECORDING_RED = Color(0xFFE53935)

/** Milliseconds as m:ss, or h:mm:ss past an hour. */
fun clock(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/**
 * The toolbar's microphone: starts, pauses and stops recording, and lists the document's recordings
 * to play, rename or delete. It's red while recording.
 */
@Composable
fun RecordButton(
    doc: Document,
    recorder: AudioRecorder,
    onRecord: () -> Unit,
    onPlay: (Recording) -> Unit,
    onRename: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val session = recorder.session
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(R.drawable.ic_mic),
                stringResource(R.string.recordings),
                tint = if (session != null) RECORDING_RED else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (session == null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.record)) },
                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(RECORDING_RED)) },
                    onClick = {
                        open = false
                        onRecord()
                    },
                )
            } else {
                DropdownMenuItem(text = { Text(stringResource(if (session.paused) R.string.resume_recording else R.string.pause_recording)) }, onClick = {
                    open = false
                    if (session.paused) recorder.resume() else recorder.pause()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.stop_recording)) }, onClick = {
                    open = false
                    recorder.stop()
                })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.recordings),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (doc.recordings.isEmpty()) {
                Text(
                    stringResource(R.string.no_recordings),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            doc.recordings.forEach { recording ->
                var menu by remember { mutableStateOf(false) }
                DropdownMenuItem(
                    text = {
                        Column(Modifier.widthIn(max = 260.dp)) {
                            Text(recording.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(clock(recording.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, stringResource(R.string.play)) },
                    trailingIcon = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                                    menu = false
                                    open = false
                                    onRename(recording)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                    menu = false
                                    open = false
                                    onDelete(recording)
                                })
                            }
                        }
                    },
                    onClick = {
                        open = false
                        onPlay(recording)
                    },
                )
            }
        }
    }
}

/** A pill showing the recording under way, with pause and stop. */
@Composable
fun RecordingPill(recorder: AudioRecorder, modifier: Modifier = Modifier) {
    val session = recorder.session ?: return
    val elapsed by produceState(session.elapsed(), session) {
        while (true) {
            value = session.elapsed()
            delay(TICK_MS)
        }
    }
    Surface(shape = RoundedCornerShape(50), shadowElevation = 4.dp, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (session.paused) MaterialTheme.colorScheme.outline else RECORDING_RED))
            Text(clock(elapsed), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
            TextButton(onClick = { if (session.paused) recorder.resume() else recorder.pause() }) {
                Text(stringResource(if (session.paused) R.string.resume_recording else R.string.pause_recording))
            }
            TextButton(onClick = recorder::stop) { Text(stringResource(R.string.stop_recording), color = RECORDING_RED) }
        }
    }
}

/** Play and pause, a seek bar with times, speed, and close, for the loaded recording. */
@Composable
fun PlaybackBar(player: AudioPlayer, modifier: Modifier = Modifier) {
    val recording = player.loaded?.second ?: return
    LaunchedEffect(player.playing) {
        while (player.playing) withFrameMillis { player.tick() }
    }
    var dragging by remember { mutableStateOf(false) }
    var dragAt by remember { mutableFloatStateOf(0f) }
    var speeds by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, modifier = modifier.widthIn(max = 720.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            IconButton(onClick = player::toggle) {
                if (player.playing) {
                    Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.pause))
                } else {
                    Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(recording.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shown = if (dragging) dragAt.toLong() else player.position
                    Text(clock(shown), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = shown.toFloat().coerceIn(0f, player.duration.toFloat().coerceAtLeast(1f)),
                        onValueChange = {
                            dragging = true
                            dragAt = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            player.seek(dragAt.toLong())
                        },
                        valueRange = 0f..player.duration.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(clock(player.duration), style = MaterialTheme.typography.labelSmall)
                }
            }
            Box {
                TextButton(onClick = { speeds = true }) { Text(speedLabel(player.speed)) }
                DropdownMenu(expanded = speeds, onDismissRequest = { speeds = false }) {
                    AudioPlayer.SPEEDS.forEach { s ->
                        DropdownMenuItem(text = { Text(speedLabel(s)) }, onClick = {
                            speeds = false
                            player.updateSpeed(s)
                        })
                    }
                }
            }
            IconButton(onClick = player::close) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
    }
}

private fun speedLabel(speed: Float) = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"

private const val TICK_MS = 250L
