package net.mildhope.nxtes.audio

import net.mildhope.nxtes.data.InkStroke

/** Playback of a recording made from clock time [start] to [end], now at clock time [now]. */
class Replay(val start: Long, val end: Long, val now: Long)

/** The stroke as far as it had been drawn [ms] after it began, or null if it hadn't begun. */
fun InkStroke.until(ms: Long): InkStroke? {
    val n = t.indexOfLast { it <= ms } + 1
    if (n <= 0) return null
    if (n == pointCount) return this
    return InkStroke(
        id, pen, color, size, tool, noiseSeed, created,
        x.copyOf(n), y.copyOf(n), t.copyOf(n), pressure?.copyOf(n), tilt?.copyOf(n), orientation?.copyOf(n),
    )
}

/**
 * The strokes as they stood at the replay's moment: those written while recording but after it
 * are left out, and one being written right then is cut short. Writing from outside the
 * recording stays as it is.
 */
fun replayed(strokes: List<InkStroke>, replay: Replay): List<InkStroke> = strokes.mapNotNull { s ->
    when {
        s.created < replay.start || s.created > replay.end -> s
        s.created > replay.now -> null
        else -> s.until(replay.now - s.created)
    }
}
