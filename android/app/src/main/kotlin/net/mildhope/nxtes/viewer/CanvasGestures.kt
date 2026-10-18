package net.mildhope.nxtes.viewer

import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.mildhope.nxtes.edit.Handle
import net.mildhope.nxtes.ink.InputMode
import net.mildhope.nxtes.ink.Tool
import net.mildhope.nxtes.ink.ToolState

/** Drags a pointer can make on the canvas. */
enum class DragKind {
    ERASE, LASSO, SELECTION, SHAPE, LASER, MASK,

    /** A tool that places something where it's tapped; dragging does nothing. */
    PLACE,
}

/** What the canvas gestures ask of the pages under them. Positions are in screen pixels. */
interface CanvasTools {
    /** The selection handle under the point, or [Handle.NONE]. */
    fun handleAt(at: Offset): Handle

    /** The drag a drawing pointer makes with the current tool, or null to let the ink layer draw. */
    fun drawingDrag(): DragKind?

    /** Whether something under the point opens when tapped, so drawing there waits to see if it's a tap. */
    fun opensOnTap(at: Offset): Boolean

    fun dragStart(kind: DragKind, at: Offset, handle: Handle)

    fun dragMove(to: Offset)

    fun dragEnd()

    /** A short touch that didn't move. */
    fun tap(at: Offset)

    /** A pen starts drawing ink. */
    fun penDown(at: Offset)

    /** The pen drawing ink was held still at the end of [path], to turn it into a shape. */
    fun penHeld(path: List<Offset>)

    fun longPress(at: Offset)

    /** Two or more fingers tapped together: two undo, three redo. */
    fun fingerTap(fingers: Int)
}

private const val PALM_REJECT_MS = 600L
private const val HOLD_TO_CONVERT_MS = 650L
private const val WHEEL_STEP_PX = 64f
private const val FINGER_TAP_MS = 300L

private class PenTracker {
    var lastSeen = Long.MIN_VALUE / 2
}

private sealed interface Hold {
    data object Held : Hold
    class Tapped(val at: Offset) : Hold
    class Moved(val event: PointerEvent) : Hold
}

private fun PointerType.isPen() = this == PointerType.Stylus || this == PointerType.Eraser

private fun PointerType.draws() = this == PointerType.Stylus || this == PointerType.Mouse

/**
 * Routes input in the initial pass, before the ink layer sees it. Pens are left alone so they draw,
 * unless the tool makes its own drag (lasso, eraser). Fingers pan and zoom, unless finger drawing is
 * on, when one finger draws and two navigate. Touches right after the pen was near are treated as a
 * resting palm. Any pointer on a selection handle moves, resizes or turns the selection, and a
 * finger held still opens the page's touch-and-hold actions.
 */
fun Modifier.canvasGestures(state: ViewportState, tools: ToolState, input: () -> CanvasTools) = pointerInput(state, tools) {
    val decay = splineBasedDecay<Float>(this)
    val pointerScope = this
    val pen = PenTracker()
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val slop = viewConfiguration.touchSlop
    coroutineScope {
        var fling: Job? = null
        pointerScope.awaitPointerEventScope {
            while (true) {
                val event = nextEvent(pen)
                if (event.type == PointerEventType.Scroll) {
                    val delta = event.changes.first().scrollDelta
                    state.wheel(delta.y * WHEEL_STEP_PX)
                    event.changes.forEach { it.consume() }
                    continue
                }
                val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                val canvas = input()

                suspend fun AwaitPointerEventScope.navigateFrom(start: PointerEvent, everyPointer: Boolean) {
                    fling?.cancel()
                    when (val hold = holdOrMove(pen, start, down, longPressMs, slop, everyPointer)) {
                        Hold.Held -> canvas.longPress(down.position)
                        is Hold.Tapped -> canvas.tap(hold.at)
                        is Hold.Moved -> {
                            val end = navigate(pen, state, hold.event, down.uptimeMillis, slop, everyPointer)
                            if (end.fingers >= 2) canvas.fingerTap(end.fingers) else fling = launch { state.fling(end.velocity, decay) }
                        }
                    }
                }

                /** Tool drags that can also be a tap or a touch and hold wait to see which it is. */
                suspend fun AwaitPointerEventScope.toolGesture(start: PointerEvent, kind: DragKind) {
                    if (kind != DragKind.LASSO && kind != DragKind.PLACE && kind != DragKind.MASK) return drag(pen, start, down.id, canvas, kind, Handle.NONE)
                    when (val hold = holdOrMove(pen, start, down, longPressMs, slop, everyPointer = true)) {
                        Hold.Held -> canvas.longPress(down.position)
                        is Hold.Tapped -> canvas.tap(hold.at)
                        is Hold.Moved -> drag(pen, hold.event, down.id, canvas, kind, Handle.NONE, from = down.position)
                    }
                }

                val handle = canvas.handleAt(down.position)
                if (handle != Handle.NONE && down.type != PointerType.Eraser) {
                    drag(pen, event, down.id, canvas, DragKind.SELECTION, handle)
                    continue
                }
                if (tools.mode == InputMode.GESTURE || (tools.annotationsHidden && tools.tool != Tool.LASER)) {
                    navigateFrom(event, everyPointer = true)
                    continue
                }
                val erasing = tools.tool == Tool.ERASER
                val toolDrag = canvas.drawingDrag() ?: DragKind.PLACE.takeIf { canvas.opensOnTap(down.position) }
                when {
                    down.type == PointerType.Eraser || (erasing && down.type.draws()) -> drag(pen, event, down.id, canvas, DragKind.ERASE, Handle.NONE)
                    down.type.draws() -> if (toolDrag != null) toolGesture(event, toolDrag) else watchPen(pen, event, down, canvas, slop)
                    down.uptimeMillis - pen.lastSeen < PALM_REJECT_MS -> swallow(pen, event) { it.type == PointerType.Touch }
                    tools.drawWithFinger && toolDrag != null -> toolGesture(event, toolDrag)
                    tools.drawWithFinger -> {
                        fling?.cancel()
                        watchPen(pen, event, down, canvas, slop, finger = true)?.let {
                            val end = navigate(pen, state, it, down.uptimeMillis, slop)
                            if (end.fingers >= 2) canvas.fingerTap(end.fingers) else fling = launch { state.fling(end.velocity, decay) }
                        }
                    }
                    else -> navigateFrom(event, everyPointer = false)
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.nextEvent(pen: PenTracker): PointerEvent =
    awaitPointerEvent(PointerEventPass.Initial).also { event ->
        event.changes.forEach { if (it.type.isPen()) pen.lastSeen = it.uptimeMillis }
    }

/** Follows one pointer until it lifts, reporting its path to [canvas], starting [from] when given. */
private suspend fun AwaitPointerEventScope.drag(
    pen: PenTracker,
    first: PointerEvent,
    pointer: PointerId,
    canvas: CanvasTools,
    kind: DragKind,
    handle: Handle,
    from: Offset? = null,
) {
    var event = first
    var started = from != null
    if (from != null) canvas.dragStart(kind, from, handle)
    while (true) {
        val change = event.changes.firstOrNull { it.id == pointer }
        event.changes.forEach { if (it.id == pointer || it.type == PointerType.Touch) it.consume() }
        if (change == null || !change.pressed) {
            if (started) canvas.dragEnd()
            return
        }
        if (started) {
            canvas.dragMove(change.position)
        } else {
            canvas.dragStart(kind, change.position, handle)
            started = true
        }
        event = nextEvent(pen)
    }
}

/**
 * Watches a pen or drawing finger laying down ink, without taking its input from the ink layer.
 * Holding still after drawing asks [canvas] to turn the line into a shape. A pen ignores resting
 * palms meanwhile; a [finger] that's joined by a second one returns that event so they navigate.
 */
private suspend fun AwaitPointerEventScope.watchPen(
    pen: PenTracker,
    first: PointerEvent,
    down: PointerInputChange,
    canvas: CanvasTools,
    slop: Float,
    finger: Boolean = false,
): PointerEvent? {
    canvas.penDown(down.position)
    val path = mutableListOf(down.position)
    var anchor = down.position
    var anchorTime = down.uptimeMillis
    var moved = false
    var held = false
    var event = first
    while (true) {
        if (finger) {
            if (event.changes.count { it.type == PointerType.Touch && it.pressed } >= 2) return event
        } else {
            event.changes.forEach { if (it.type == PointerType.Touch) it.consume() }
        }
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) return null
        if (change.position != path.last()) path += change.position
        if ((change.position - anchor).getDistance() > slop) {
            anchor = change.position
            anchorTime = change.uptimeMillis
            moved = true
        }
        if (moved && !held && change.uptimeMillis - anchorTime >= HOLD_TO_CONVERT_MS) {
            held = true
            canvas.penHeld(path.toList())
        }
        val wait = if (moved && !held) (HOLD_TO_CONVERT_MS - (change.uptimeMillis - anchorTime)).coerceAtLeast(1) else null
        val next = if (wait == null) nextEvent(pen) else withTimeoutOrNull(wait) { nextEvent(pen) }
        if (next == null) {
            held = true
            canvas.penHeld(path.toList())
            event = nextEvent(pen)
        } else {
            event = next
        }
    }
}

/** Waits to see whether the first touch is held still, tapped, or moved (or joined by another). */
private suspend fun AwaitPointerEventScope.holdOrMove(
    pen: PenTracker,
    first: PointerEvent,
    down: PointerInputChange,
    timeout: Long,
    slop: Float,
    everyPointer: Boolean,
): Hold {
    fun counts(change: PointerInputChange) = everyPointer || change.type == PointerType.Touch
    var event = first
    while (true) {
        val pressed = event.changes.filter { counts(it) && it.pressed }
        if (pressed.isEmpty()) return Hold.Tapped(down.position)
        if (pressed.size > 1 || (pressed.first().position - down.position).getDistance() > slop) return Hold.Moved(event)
        val elapsed = (event.changes.maxOfOrNull { it.uptimeMillis } ?: down.uptimeMillis) - down.uptimeMillis
        event = withTimeoutOrNull((timeout - elapsed).coerceAtLeast(1)) { nextEvent(pen) } ?: break
    }
    swallow(pen, event) { counts(it) }
    return Hold.Held
}

/** Consumes matching pointers until none of them is still pressed. */
private suspend fun AwaitPointerEventScope.swallow(
    pen: PenTracker,
    first: PointerEvent,
    matches: (PointerInputChange) -> Boolean,
) {
    var event = first
    while (true) {
        event.changes.filter(matches).forEach { it.consume() }
        if (event.changes.none { matches(it) && it.pressed }) return
        event = nextEvent(pen)
    }
}

/** Pans and zooms until every finger lifts; returns the release velocity for a fling. */
/** How a scroll or zoom ended: its speed, or the number of fingers when it was really a quick tap. */
private class NavigationEnd(val velocity: Velocity, val fingers: Int)

/**
 * Scrolls and zooms with the pointers until they all lift. Fingers that come down together since
 * [startedAt] and lift quickly without moving count as a multi-finger tap instead.
 */
private suspend fun AwaitPointerEventScope.navigate(
    pen: PenTracker,
    state: ViewportState,
    first: PointerEvent,
    startedAt: Long,
    slop: Float,
    everyPointer: Boolean = false,
): NavigationEnd {
    fun counts(change: PointerInputChange) = everyPointer || change.type == PointerType.Touch
    val tracker = VelocityTracker()
    var travelled = Offset.Zero
    var moved = 0f
    var zoomed = 1f
    var fingers = 0
    var event = first
    while (true) {
        val zoomChange = event.calculateZoom()
        val pan = event.calculatePan()
        val centroid = event.calculateCentroid(useCurrent = false)
        if (centroid.isValid() && (zoomChange != 1f || pan != Offset.Zero)) {
            state.transformBy(centroid, pan, zoomChange)
        }
        event.changes.forEach { if (counts(it)) it.consume() }
        travelled += pan
        moved += pan.getDistance()
        zoomed *= zoomChange
        fingers = maxOf(fingers, event.changes.count { it.type == PointerType.Touch && it.pressed })
        event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, travelled) }
        if (event.changes.none { counts(it) && it.pressed }) {
            val quick = (event.changes.firstOrNull()?.uptimeMillis ?: startedAt) - startedAt < FINGER_TAP_MS
            val still = moved < slop * 2 && zoomed in 0.9f..1.1f
            return NavigationEnd(tracker.calculateVelocity(), if (quick && still) fingers else 0)
        }
        event = nextEvent(pen)
    }
}
