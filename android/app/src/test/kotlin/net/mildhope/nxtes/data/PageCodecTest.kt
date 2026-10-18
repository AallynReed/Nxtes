package net.mildhope.nxtes.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCodecTest {
    private fun stroke(pressure: FloatArray? = floatArrayOf(0.2f, 0.5f, 1f)) = InkStroke(
        id = "s1",
        pen = Pen.FOUNTAIN,
        color = 0xFF2244AA.toInt(),
        size = 1.5f,
        tool = InputTool.STYLUS,
        noiseSeed = 7,
        created = 1_700_000_000_000,
        x = floatArrayOf(10.25f, 11.5f, 9.75f),
        y = floatArrayOf(100f, 100.01f, 99f),
        t = intArrayOf(0, 8, 16),
        pressure = pressure,
    )

    @Test
    fun roundTripKeepsEveryChannel() {
        val decoded = PageCodec.decode(PageCodec.encode(PageContent(strokes = listOf(stroke())))).strokes.single()

        assertEquals("s1", decoded.id)
        assertEquals(Pen.FOUNTAIN, decoded.pen)
        assertEquals(0xFF2244AA.toInt(), decoded.color)
        assertEquals(1.5f, decoded.size)
        assertEquals(InputTool.STYLUS, decoded.tool)
        assertEquals(7, decoded.noiseSeed)
        assertEquals(1_700_000_000_000, decoded.created)
        assertArrayEquals(floatArrayOf(10.25f, 11.5f, 9.75f), decoded.x, 0.005f)
        assertArrayEquals(floatArrayOf(100f, 100.01f, 99f), decoded.y, 0.005f)
        assertArrayEquals(intArrayOf(0, 8, 16), decoded.t)
        assertArrayEquals(floatArrayOf(0.2f, 0.5f, 1f), decoded.pressure, 0.001f)
        assertNull(decoded.tilt)
    }

    @Test
    fun missingPressureStaysMissing() {
        assertNull(PageCodec.decode(PageCodec.encode(PageContent(strokes = listOf(stroke(pressure = null))))).strokes.single().pressure)
    }

    @Test
    fun emptyPageDecodesToNoStrokes() {
        assertTrue(PageCodec.decode(PageCodec.encode(PageContent.EMPTY)).isEmpty)
    }

    @Test
    fun xIsPackedZigzagDeltasAsInTheProto() {
        val bytes = PageCodec.encode(PageContent(strokes = listOf(stroke()))).toList()
        // Field 7, length 6: x deltas 1025, 125, -175 zigzag to 2050, 250, 349 as varints.
        val expected = listOf(0x3A, 0x06, 0x82, 0x10, 0xFA, 0x01, 0xDD, 0x02).map { it.toByte() }
        assertTrue("packed sint32 x not found in $bytes", bytes.windowed(expected.size).contains(expected))
    }

    @Test
    fun objectsNotesMasksMarksAndLinksRoundTrip() {
        val content = PageContent(
            objects = listOf(
                Shape("sh", ShapeKind.ELLIPSE, floatArrayOf(1f, 9f, 9f, 1f), floatArrayOf(2f, 2f, 8f, 8f), 0xFF112233.toInt(), 2f, fill = 0x40FF0000, line = LineStyle.DASHED),
                TextBox("tx", "Hello", 10f, 20f, 100f, 18f, rotation = 15f, font = "blob:abc", size = 12f, bold = true, align = TextAlignment.JUSTIFIED),
                Picture("pi", "def", 50f, 60f, 40f, 30f, cropLeft = 0.1f, outline = listOf(0f, 0f, 1f, 0f, 1f, 1f), sticker = true),
            ),
            notes = listOf(StickyNote("no", 5f, 6f, "Remember", 0xFFFFE082.toInt(), 99)),
            masks = listOf(Mask("ma", MaskKind.PEN, floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), 12f, 0xFFFF8A65.toInt(), tries = 3, correct = 2)),
            marks = listOf(TextMark("mk", MarkKind.UNDERLINE, 0xFF0000FF.toInt(), floatArrayOf(1f, 2f, 3f, 4f), "text")),
            links = listOf(Link("lp", 1f, 2f, 30f, 40f, pageId = "p2"), Link("lu", 5f, 6f, 7f, 8f, url = "https://example.org")),
        )

        val decoded = PageCodec.decode(PageCodec.encode(content))

        val shape = decoded.objects[0] as Shape
        assertEquals(ShapeKind.ELLIPSE to LineStyle.DASHED, shape.kind to shape.line)
        assertArrayEquals(floatArrayOf(2f, 2f, 8f, 8f), shape.ys, 0f)
        assertEquals(0x40FF0000, shape.fill)
        assertEquals(content.objects[1], decoded.objects[1])
        assertEquals(content.objects[2], decoded.objects[2])
        assertEquals(content.notes, decoded.notes)
        assertEquals(Triple(MaskKind.PEN, 3, 2), decoded.masks.single().let { Triple(it.kind, it.tries, it.correct) })
        assertEquals("text", decoded.marks.single().text)
        assertEquals(content.links, decoded.links)
        assertEquals(setOf("abc", "def"), decoded.blobs)
    }

    @Test
    fun leftAlignedTextIsWrittenAsBeforeAlignmentExisted() {
        fun bytes(align: TextAlignment) = PageCodec.encode(PageContent(objects = listOf(TextBox("t", "Hi", 1f, 2f, 30f, 10f, align = align))))

        // Field 14 is left out at its default, so older files and older apps see the same bytes.
        assertEquals(bytes(TextAlignment.LEFT).size + 2, bytes(TextAlignment.RIGHT).size)
        assertEquals(TextAlignment.LEFT, (PageCodec.decode(bytes(TextAlignment.LEFT)).objects.single() as TextBox).align)
        assertEquals(TextAlignment.RIGHT, (PageCodec.decode(bytes(TextAlignment.RIGHT)).objects.single() as TextBox).align)
    }

    @Test
    fun quarterTurnsMoveEverythingWithThePage() {
        val turn = Affine.quarterTurn(clockwise = true, width = 100f, height = 200f)
        val box = TextBox("t", "x", 10f, 20f, 30f, 10f).transformed(turn)

        assertEquals(200f - 25f, box.centerX, 0.001f)
        assertEquals(25f, box.centerY, 0.001f)
        assertEquals(90f, box.rotation, 0.001f)
        val link = Link("l", 10f, 20f, 30f, 10f, url = "u").transformed(turn)
        assertEquals(listOf(170f, 10f, 10f, 30f), listOf(link.x, link.y, link.width, link.height))
    }
}
