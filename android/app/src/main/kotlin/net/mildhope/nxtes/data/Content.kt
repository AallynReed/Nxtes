package net.mildhope.nxtes.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything on a page besides its background, in page points. Drawn bottom to top: text marks,
 * objects (in list order), highlighter ink, other ink, sticky notes, masks.
 */
data class PageContent(
    val strokes: List<InkStroke> = emptyList(),
    val objects: List<PageObject> = emptyList(),
    val notes: List<StickyNote> = emptyList(),
    val masks: List<Mask> = emptyList(),
    val marks: List<TextMark> = emptyList(),
) {
    val isEmpty get() = strokes.isEmpty() && objects.isEmpty() && notes.isEmpty() && masks.isEmpty() && marks.isEmpty()

    /** Blobs in the document package that this content refers to. */
    val blobs: Set<String>
        get() = objects.mapNotNull {
            when (it) {
                is Picture -> it.blob
                is TextBox -> it.font.removePrefix(FONT_BLOB).takeIf { _ -> it.font.startsWith(FONT_BLOB) }
                is Shape -> null
            }
        }.toSet()

    fun transformed(t: Affine) = PageContent(
        strokes = strokes.map { it.transformed(t) },
        objects = objects.map { it.transformed(t) },
        notes = notes.map { note -> t.map(note.x, note.y).let { (x, y) -> note.copy(x = x, y = y) } },
        masks = masks.map { it.transformed(t) },
        marks = marks.map { it.transformed(t) },
    )

    companion object {
        val EMPTY = PageContent()

        /** Prefix of [TextBox.font] for a font file stored as a document blob. */
        const val FONT_BLOB = "blob:"
    }
}

sealed interface PageObject {
    val id: String

    fun transformed(t: Affine): PageObject
}

enum class ShapeKind(val code: Int) {
    LINE(1), ARROW(2), RECTANGLE(3), ELLIPSE(4), TRIANGLE(5), POLYGON(6), CURVE(7);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: LINE
    }
}

enum class LineStyle(val code: Int) {
    SOLID(0), DASHED(1), DOTTED(2);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: SOLID
    }
}

/**
 * A drawn shape. [xs]/[ys] are its corners: two for lines and arrows, four for rectangles and the
 * box around an ellipse (clockwise from top-left), the vertices for polygons and triangles, and the
 * points a curve passes through. [fill] is 0 for no fill.
 */
class Shape(
    override val id: String,
    val kind: ShapeKind,
    val xs: FloatArray,
    val ys: FloatArray,
    val color: Int,
    val width: Float,
    val fill: Int = 0,
    val line: LineStyle = LineStyle.SOLID,
) : PageObject {
    init {
        require(xs.size == ys.size) { "Point channels differ in length" }
    }

    override fun transformed(t: Affine): Shape {
        val x = FloatArray(xs.size)
        val y = FloatArray(ys.size)
        for (i in xs.indices) t.map(xs[i], ys[i]).let { (a, b) -> x[i] = a; y[i] = b }
        return copy(xs = x, ys = y, width = width * t.scale)
    }

    fun copy(
        id: String = this.id,
        xs: FloatArray = this.xs,
        ys: FloatArray = this.ys,
        color: Int = this.color,
        width: Float = this.width,
        fill: Int = this.fill,
        line: LineStyle = this.line,
    ) = Shape(id, kind, xs, ys, color, width, fill, line)
}

/**
 * A text box whose unrotated top-left corner is at [x],[y], turned by [rotation] degrees about its
 * centre. [font] is a system family name, or [PageContent.FONT_BLOB] plus a font file's SHA-256.
 */
data class TextBox(
    override val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val font: String = "sans-serif",
    val size: Float = 14f,
    val color: Int = 0xFF000000.toInt(),
    val background: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) : PageObject {
    val centerX get() = x + width / 2
    val centerY get() = y + height / 2

    override fun transformed(t: Affine): TextBox {
        val (cx, cy) = t.map(centerX, centerY)
        val w = width * t.scale
        val h = height * t.scale
        return copy(x = cx - w / 2, y = cy - h / 2, width = w, height = h, size = size * t.scale, rotation = rotation + t.degrees)
    }
}

/**
 * An image or sticker centred on [cx],[cy], turned by [rotation] degrees. It shows the part of the
 * image inside the crop box (fractions of the image), clipped to [outline] when set: a polygon of
 * x,y pairs as fractions of the crop box.
 */
data class Picture(
    override val id: String,
    val blob: String,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val outline: List<Float> = emptyList(),
    val sticker: Boolean = false,
) : PageObject {
    override fun transformed(t: Affine): Picture {
        val (x, y) = t.map(cx, cy)
        return copy(cx = x, cy = y, width = width * t.scale, height = height * t.scale, rotation = rotation + t.degrees)
    }
}

data class StickyNote(val id: String, val x: Float, val y: Float, val text: String, val color: Int, val created: Long)

enum class MaskKind(val code: Int) {
    TAPE(1), PEN(2);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: TAPE
    }
}

/** A cover over the page: tape is a four-corner box, pen masks are thick lines of [width]. */
class Mask(
    val id: String,
    val kind: MaskKind,
    val xs: FloatArray,
    val ys: FloatArray,
    val width: Float,
    val color: Int,
    val tries: Int = 0,
    val correct: Int = 0,
) {
    fun transformed(t: Affine): Mask {
        val x = FloatArray(xs.size)
        val y = FloatArray(ys.size)
        for (i in xs.indices) t.map(xs[i], ys[i]).let { (a, b) -> x[i] = a; y[i] = b }
        return copy(xs = x, ys = y, width = width * t.scale)
    }

    fun copy(
        id: String = this.id,
        xs: FloatArray = this.xs,
        ys: FloatArray = this.ys,
        width: Float = this.width,
        color: Int = this.color,
        tries: Int = this.tries,
        correct: Int = this.correct,
    ) = Mask(id, kind, xs, ys, width, color, tries, correct)
}

enum class MarkKind(val code: Int) {
    HIGHLIGHT(1), UNDERLINE(2), STRIKEOUT(3);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: HIGHLIGHT
    }
}

/** A mark on PDF text: [rects] holds x, y, width, height for each line it covers. */
class TextMark(val id: String, val kind: MarkKind, val color: Int, val rects: FloatArray, val text: String) {
    fun transformed(t: Affine): TextMark {
        val out = FloatArray(rects.size)
        for (i in rects.indices step 4) {
            val (x0, y0) = t.map(rects[i], rects[i + 1])
            val (x1, y1) = t.map(rects[i] + rects[i + 2], rects[i + 1] + rects[i + 3])
            out[i] = min(x0, x1); out[i + 1] = min(y0, y1)
            out[i + 2] = max(x0, x1) - out[i]; out[i + 3] = max(y0, y1) - out[i + 1]
        }
        return TextMark(id, kind, color, out, text)
    }
}

/** A 2D affine transform: x' = a·x + c·y + e, y' = b·x + d·y + f. */
data class Affine(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
    fun map(x: Float, y: Float) = Pair(a * x + c * y + e, b * x + d * y + f)

    /** How much lengths grow, for uniform transforms. */
    val scale: Float get() = sqrt(kotlin.math.abs(a * d - b * c))

    /** Rotation in degrees, clockwise on screen. */
    val degrees: Float get() = Math.toDegrees(atan2(b, a).toDouble()).toFloat()

    /** This transform, then [next]. */
    fun then(next: Affine) = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        e = next.a * e + next.c * f + next.e,
        f = next.b * e + next.d * f + next.f,
    )

    companion object {
        fun translate(dx: Float, dy: Float) = Affine(e = dx, f = dy)

        /** Scales by [s] and turns by [degrees] about [px],[py]. */
        fun around(px: Float, py: Float, s: Float, degrees: Float): Affine {
            val r = Math.toRadians(degrees.toDouble())
            val cos = (cos(r) * s).toFloat()
            val sin = (sin(r) * s).toFloat()
            return translate(-px, -py).then(Affine(cos, sin, -sin, cos)).then(translate(px, py))
        }

        /** A quarter turn of a [width]x[height] page, keeping it in positive coordinates. */
        fun quarterTurn(clockwise: Boolean, width: Float, height: Float) =
            if (clockwise) Affine(0f, 1f, -1f, 0f, height, 0f) else Affine(0f, -1f, 1f, 0f, 0f, width)
    }
}

/** Distance between two points. */
fun distance(x0: Float, y0: Float, x1: Float, y1: Float) = hypot(x1 - x0, y1 - y0)
