package net.mildhope.nxtes.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkCodecTest {
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
        val decoded = InkCodec.decode(InkCodec.encode(listOf(stroke()))).single()

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
        assertNull(InkCodec.decode(InkCodec.encode(listOf(stroke(pressure = null)))).single().pressure)
    }

    @Test
    fun emptyPageDecodesToNoStrokes() {
        assertTrue(InkCodec.decode(InkCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun xIsPackedZigzagDeltasAsInTheProto() {
        val bytes = InkCodec.encode(listOf(stroke())).toList()
        // Field 7, length 6: x deltas 1025, 125, -175 zigzag to 2050, 250, 349 as varints.
        val expected = listOf(0x3A, 0x06, 0x82, 0x10, 0xFA, 0x01, 0xDD, 0x02).map { it.toByte() }
        assertTrue("packed sint32 x not found in $bytes", bytes.windowed(expected.size).contains(expected))
    }
}
