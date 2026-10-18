package net.mildhope.nxtes.audio

import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.InputTool
import net.mildhope.nxtes.data.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReplayTest {
    private fun stroke(id: String, created: Long) = InkStroke(
        id = id, pen = Pen.BALLPOINT, color = 0, size = 1f, tool = InputTool.STYLUS, noiseSeed = 0, created = created,
        x = floatArrayOf(0f, 1f, 2f, 3f), y = floatArrayOf(0f, 0f, 0f, 0f), t = intArrayOf(0, 100, 200, 300),
    )

    @Test
    fun aStrokeIsCutWhereThePenWasAtThatMoment() {
        val s = stroke("s", 1_000)

        assertEquals(2, s.until(150)!!.pointCount)
        assertSame(s, s.until(5_000))
        assertNull(stroke("late", 1_000).until(-1))
    }

    @Test
    fun replayHidesLaterWritingFromTheRecordingOnly() {
        val before = stroke("before", 500)
        val early = stroke("early", 2_000)
        val drawing = stroke("drawing", 3_000)
        val later = stroke("later", 4_000)
        val after = stroke("after", 9_000)

        val shown = replayed(listOf(before, early, drawing, later, after), Replay(start = 1_000, end = 5_000, now = 3_150))

        assertEquals(listOf("before", "early", "drawing", "after"), shown.map { it.id })
        assertEquals(2, shown[2].pointCount)
    }
}
