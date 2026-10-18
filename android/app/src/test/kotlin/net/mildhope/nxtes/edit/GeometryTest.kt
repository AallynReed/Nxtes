package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.InputTool
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.data.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeometryTest {
    private fun stroke(id: String, pen: Pen, vararg points: Float) = InkStroke(
        id = id, pen = pen, color = 0, size = 2f, tool = InputTool.STYLUS, noiseSeed = 0, created = 0,
        x = FloatArray(points.size / 2) { points[it * 2] }, y = FloatArray(points.size / 2) { points[it * 2 + 1] },
        t = IntArray(points.size / 2),
    )

    private val content = PageContent(
        strokes = listOf(
            stroke("in", Pen.BALLPOINT, 10f, 10f, 20f, 20f, 30f, 10f),
            stroke("half", Pen.BALLPOINT, 40f, 40f, 150f, 150f, 200f, 200f),
            stroke("marker", Pen.HIGHLIGHTER, 15f, 15f, 25f, 25f),
        ),
        objects = listOf(
            TextBox("text", "hi", 60f, 10f, 20f, 10f),
            Picture("far", "blob", 300f, 300f, 40f, 40f),
        ),
        notes = listOf(StickyNote("note", 5f, 70f, "", 0, 0)),
    )
    private val square = floatArrayOf(0f, 100f, 100f, 0f) to floatArrayOf(0f, 0f, 100f, 100f)

    @Test
    fun lassoPicksWhatsMostlyInsideAndHonoursTypes() {
        val all = Geometry.select(content, square.first, square.second, LassoType.entries.toSet())
        assertEquals(setOf("in", "marker"), all.strokes)
        assertEquals(setOf("text"), all.objects)
        assertEquals(setOf("note"), all.notes)

        val pensOnly = Geometry.select(content, square.first, square.second, setOf(LassoType.PEN))
        assertEquals(Picked(strokes = setOf("in")), pensOnly)
    }

    @Test
    fun touchAndHoldFindsTheTopmostObject() {
        assertEquals(Picked(objects = setOf("text")), Geometry.hit(content, 70f, 15f, 2f))
        assertEquals(Picked(notes = setOf("note")), Geometry.hit(content, 10f, 75f, 2f))
        assertNull(Geometry.hit(content, 150f, 150f, 2f))
    }

    @Test
    fun movingTheSelectionMovesOnlyPickedItems() {
        val picked = Picked(strokes = setOf("in"), objects = setOf("text"))
        val moved = picked.transform(content, Affine.translate(5f, 0f))

        assertEquals(15f, moved.strokes.first { it.id == "in" }.x[0], 0.001f)
        assertEquals(40f, moved.strokes.first { it.id == "half" }.x[0], 0.001f)
        assertEquals(65f, (moved.objects.first { it.id == "text" } as TextBox).x, 0.001f)
        assertEquals(Box(9f, 9f, 31f, 21f), Geometry.bounds(picked.of(content).copy(objects = emptyList())))
    }

    @Test
    fun scalingAboutACornerKeepsThatCorner() {
        val t = Affine.around(10f, 10f, 2f, 0f)
        assertEquals(10f to 10f, t.map(10f, 10f))
        assertEquals(30f to 10f, t.map(20f, 10f))
        assertEquals(2f, t.scale, 0.0001f)
    }
}
