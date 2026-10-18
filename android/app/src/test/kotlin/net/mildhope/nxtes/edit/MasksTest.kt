package net.mildhope.nxtes.edit

import androidx.compose.ui.geometry.Offset
import net.mildhope.nxtes.data.PageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasksTest {
    @Test
    fun tapeIsTheDraggedBoxWhicheverWayItWasDragged() {
        val tape = MaskBuilder.tape("t", 50f, 40f, 10f, 20f, 0)!!

        assertEquals(listOf(10f, 50f, 50f, 10f), tape.xs.toList())
        assertEquals(listOf(20f, 20f, 40f, 40f), tape.ys.toList())
        assertTrue(Geometry.hit(tape, 30f, 30f, 0f))
        assertFalse(Geometry.hit(tape, 60f, 30f, 0f))
        assertNull(MaskBuilder.tape("t", 0f, 0f, 2f, 30f, 0))
    }

    @Test
    fun penMasksDropCrowdedPointsAndAreHitAlongTheirWidth() {
        val points = (0..100).map { Offset(it.toFloat(), 0f) }
        val pen = MaskBuilder.pen("p", points, 10f, 0)!!

        assertEquals(101, points.size)
        assertEquals(101, pen.xs.size)
        assertTrue(MaskBuilder.pen("p", points, 40f, 0)!!.xs.size < 30)
        assertTrue(Geometry.hit(pen, 50f, 4f, 0f))
        assertFalse(Geometry.hit(pen, 50f, 8f, 0f))
    }

    @Test
    fun scoresAddUpAcrossMasksAndTouchAndHoldFindsMasks() {
        val a = MaskBuilder.tape("a", 0f, 0f, 10f, 10f, 0)!!.copy(tries = 3, correct = 2)
        val b = MaskBuilder.tape("b", 20f, 0f, 30f, 10f, 0)!!.copy(tries = 1, correct = 0)

        assertEquals(2 to 4, score(listOf(a, b)))
        assertEquals(setOf("b"), Geometry.hit(PageContent(masks = listOf(a, b)), 25f, 5f, 1f)?.masks)
    }
}
