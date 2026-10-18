package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ShapeRecognizerTest {
    private val all = ShapeKind.entries.toSet()
    private val wobble = Random(7)

    /** Points along the polygon's edges with a little hand wobble, ending near the start. */
    private fun outline(vararg corners: Pair<Float, Float>, perEdge: Int = 20): Pair<FloatArray, FloatArray> {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        for (i in corners.indices) {
            val (ax, ay) = corners[i]
            val (bx, by) = corners[(i + 1) % corners.size]
            for (k in 0 until perEdge) {
                val f = k / perEdge.toFloat()
                xs += ax + (bx - ax) * f + wobble.nextFloat() - 0.5f
                ys += ay + (by - ay) * f + wobble.nextFloat() - 0.5f
            }
        }
        xs += corners[0].first + 2f
        ys += corners[0].second + 1f
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test
    fun straightishLineBecomesALine() {
        val xs = FloatArray(30) { it * 4f }
        val ys = FloatArray(30) { 50f + (wobble.nextFloat() - 0.5f) * 2f }
        val shape = ShapeRecognizer.recognize(xs, ys, all)!!
        assertEquals(ShapeKind.LINE, shape.kind)
        assertEquals(2, shape.xs.size)
    }

    @Test
    fun wavyOpenLineIsLeftAlone() {
        val xs = FloatArray(40) { it * 4f }
        val ys = FloatArray(40) { 50f + sin(it / 3f) * 20f }
        assertNull(ShapeRecognizer.recognize(xs, ys, all))
    }

    @Test
    fun closedShapesAreRecognised() {
        val (tx, ty) = outline(50f to 0f, 100f to 90f, 0f to 90f)
        assertEquals(ShapeKind.TRIANGLE, ShapeRecognizer.recognize(tx, ty, all)!!.kind)

        val (rx, ry) = outline(0f to 0f, 120f to 0f, 120f to 80f, 0f to 80f)
        val rectangle = ShapeRecognizer.recognize(rx, ry, all)!!
        assertEquals(ShapeKind.RECTANGLE, rectangle.kind)
        assertEquals(4, rectangle.xs.size)

        val n = 60
        val ex = FloatArray(n) { 100f + cos(it * 2 * Math.PI / n).toFloat() * 80f + wobble.nextFloat() }
        val ey = FloatArray(n) { 100f + sin(it * 2 * Math.PI / n).toFloat() * 50f + wobble.nextFloat() }
        assertEquals(ShapeKind.ELLIPSE, ShapeRecognizer.recognize(ex, ey, all)!!.kind)
    }

    @Test
    fun disabledKindsAreNotProduced() {
        val (rx, ry) = outline(0f to 0f, 120f to 0f, 120f to 80f, 0f to 80f)
        assertNull(ShapeRecognizer.recognize(rx, ry, setOf(ShapeKind.LINE, ShapeKind.ELLIPSE)))
    }
}
