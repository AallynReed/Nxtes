package net.mildhope.nxtes.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class CanvasGesturesTest {
    private val finger = Offset(100f, 100f)

    @Test
    fun fingerTapIsConsumedSoTheInkLayerSkipsIt() {
        val down = touch(finger, pressed = true, wasPressed = false)
        val up = touch(finger, pressed = false, wasPressed = true)
        val hold = Feed(listOf(PointerEvent(listOf(up)))).holdOrMove(down, everyPointer = false)
        assertTrue(hold is Hold.Tapped)
        assertTrue(down.isConsumed)
        assertTrue(up.isConsumed)
    }

    @Test
    fun heldFingerIsConsumedUntilItLifts() {
        val down = touch(finger, pressed = true, wasPressed = false)
        val up = touch(finger, pressed = false, wasPressed = true)
        val hold = Feed(listOf(null, PointerEvent(listOf(up)))).holdOrMove(down, everyPointer = false)
        assertTrue(hold is Hold.Held)
        assertTrue(down.isConsumed)
        assertTrue(up.isConsumed)
    }

    @Test
    fun penBesideATappingFingerIsLeftToDraw() {
        val down = touch(finger, pressed = true, wasPressed = false)
        val penDown = touch(Offset(500f, 500f), pressed = true, wasPressed = false, id = 2, type = PointerType.Stylus)
        val up = touch(finger, pressed = false, wasPressed = true)
        val penMove = touch(Offset(520f, 500f), pressed = true, wasPressed = true, id = 2, type = PointerType.Stylus)
        val feed = Feed(listOf(PointerEvent(listOf(touch(finger, pressed = true, wasPressed = true), penDown)), PointerEvent(listOf(up, penMove))))
        assertTrue(feed.holdOrMove(down, everyPointer = false) is Hold.Tapped)
        assertTrue(up.isConsumed)
        assertFalse(penDown.isConsumed)
        assertFalse(penMove.isConsumed)
    }

    private fun touch(at: Offset, pressed: Boolean, wasPressed: Boolean, id: Long = 1, type: PointerType = PointerType.Touch) =
        PointerInputChange(PointerId(id), 0, at, pressed, 0, at, wasPressed, isInitiallyConsumed = false, type = type)

    private fun Feed.holdOrMove(down: PointerInputChange, everyPointer: Boolean): Hold {
        var result: Result<Hold>? = null
        val block: suspend AwaitPointerEventScope.() -> Hold = { holdOrMove(PenTracker(), PointerEvent(listOf(down)), down, 500, 8f, everyPointer) }
        block.startCoroutine(this, Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    /** Hands out [events] in order; a null stands for a wait that times out. */
    private class Feed(events: List<PointerEvent?>) : AwaitPointerEventScope {
        private val queue = ArrayDeque(events)
        override val size = IntSize(1000, 1000)
        override val currentEvent get() = error("unused")
        override val viewConfiguration: ViewConfiguration get() = error("unused")
        override val density = 1f
        override val fontScale = 1f

        override suspend fun awaitPointerEvent(pass: PointerEventPass) = checkNotNull(queue.removeFirst())

        override suspend fun <T> withTimeoutOrNull(timeMillis: Long, block: suspend AwaitPointerEventScope.() -> T): T? {
            if (queue.first() == null) {
                queue.removeFirst()
                return null
            }
            return block(this)
        }
    }
}
