package net.mildhope.nxtes.edit

import net.mildhope.nxtes.data.Affine
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.data.Mask
import net.mildhope.nxtes.data.MaskKind
import net.mildhope.nxtes.data.PageContent
import net.mildhope.nxtes.data.PageObject
import net.mildhope.nxtes.data.Pen
import net.mildhope.nxtes.data.Picture
import net.mildhope.nxtes.data.Shape
import net.mildhope.nxtes.data.StickyNote
import net.mildhope.nxtes.data.TextBox
import net.mildhope.nxtes.page.PagePainter
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** An axis-aligned box in page points. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun inflate(by: Float) = Box(left - by, top - by, right + by, bottom + by)

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    companion object {
        fun of(xs: FloatArray, ys: FloatArray, pad: Float = 0f) =
            Box(xs.min() - pad, ys.min() - pad, xs.max() + pad, ys.max() + pad)
    }
}

/** Kinds of things a lasso can pick up. */
enum class LassoType { PEN, HIGHLIGHTER, MASK, SHAPE, IMAGE, TEXT, NOTE }

/** The ids of picked items on one page. */
data class Picked(
    val strokes: Set<String> = emptySet(),
    val objects: Set<String> = emptySet(),
    val notes: Set<String> = emptySet(),
    val masks: Set<String> = emptySet(),
) {
    val isEmpty get() = strokes.isEmpty() && objects.isEmpty() && notes.isEmpty() && masks.isEmpty()

    /** Only the picked items. */
    fun of(content: PageContent) = PageContent(
        strokes = content.strokes.filter { it.id in strokes },
        objects = content.objects.filter { it.id in objects },
        notes = content.notes.filter { it.id in notes },
        masks = content.masks.filter { it.id in masks },
    )

    /** Everything except the picked items. */
    fun without(content: PageContent) = content.copy(
        strokes = content.strokes.filter { it.id !in strokes },
        objects = content.objects.filter { it.id !in objects },
        notes = content.notes.filter { it.id !in notes },
        masks = content.masks.filter { it.id !in masks },
    )

    /** The content with each picked item changed by [change] for its kind, in place. */
    fun map(
        content: PageContent,
        stroke: (InkStroke) -> InkStroke = { it },
        obj: (PageObject) -> PageObject = { it },
        note: (StickyNote) -> StickyNote = { it },
        mask: (Mask) -> Mask = { it },
    ) = content.copy(
        strokes = content.strokes.map { if (it.id in strokes) stroke(it) else it },
        objects = content.objects.map { if (it.id in objects) obj(it) else it },
        notes = content.notes.map { if (it.id in notes) note(it) else it },
        masks = content.masks.map { if (it.id in masks) mask(it) else it },
    )

    fun transform(content: PageContent, t: Affine) = map(
        content,
        stroke = { s -> PageContent(strokes = listOf(s)).transformed(t).strokes.single() },
        obj = { it.transformed(t) },
        note = { n -> t.map(n.x, n.y).let { (x, y) -> n.copy(x = x, y = y) } },
        mask = { it.transformed(t) },
    )

    companion object {
        /** Ids of everything in [content]. */
        fun all(content: PageContent) = Picked(
            content.strokes.map { it.id }.toSet(),
            content.objects.map { it.id }.toSet(),
            content.notes.map { it.id }.toSet(),
            content.masks.map { it.id }.toSet(),
        )
    }
}

object Geometry {
    /** Whether the point is inside the polygon, by ray casting. */
    fun inside(x: Float, y: Float, xs: FloatArray, ys: FloatArray): Boolean {
        var result = false
        var j = xs.lastIndex
        for (i in xs.indices) {
            if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) result = !result
            j = i
        }
        return result
    }

    /** The four corners of a [width]x[height] box centred on [cx],[cy] and turned by [degrees]. */
    fun corners(cx: Float, cy: Float, width: Float, height: Float, degrees: Float): Pair<FloatArray, FloatArray> {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val dx = floatArrayOf(-width / 2, width / 2, width / 2, -width / 2)
        val dy = floatArrayOf(-height / 2, -height / 2, height / 2, height / 2)
        return FloatArray(4) { cx + dx[it] * c - dy[it] * s } to FloatArray(4) { cy + dx[it] * s + dy[it] * c }
    }

    fun outline(o: PageObject): Pair<FloatArray, FloatArray> = when (o) {
        is Shape -> o.xs to o.ys
        is TextBox -> corners(o.centerX, o.centerY, o.width, o.height, o.rotation)
        is Picture -> corners(o.cx, o.cy, o.width, o.height, o.rotation)
    }

    fun bounds(o: PageObject): Box {
        val (xs, ys) = outline(o)
        return Box.of(xs, ys, if (o is Shape) o.width / 2 else 0f)
    }

    fun bounds(s: InkStroke) = Box.of(s.x, s.y, s.size / 2)

    fun bounds(n: StickyNote) = Box(n.x, n.y, n.x + PagePainter.NOTE_SIZE, n.y + PagePainter.NOTE_SIZE)

    fun bounds(m: Mask) = Box.of(m.xs, m.ys, if (m.kind == MaskKind.PEN) m.width / 2 else 0f)

    /** The box around everything in [content], or null if it's empty. */
    fun bounds(content: PageContent): Box? =
        (content.strokes.map(::bounds) + content.objects.map(::bounds) + content.notes.map(::bounds) + content.masks.map(::bounds))
            .reduceOrNull(Box::union)

    /**
     * What the lasso polygon [xs]/[ys] encloses: strokes and pen masks with most of their points
     * inside, and objects, notes and tape whose centres are inside.
     */
    fun select(content: PageContent, xs: FloatArray, ys: FloatArray, types: Set<LassoType>): Picked {
        if (xs.size < 3) return Picked()
        fun mostlyInside(px: FloatArray, py: FloatArray) = px.indices.count { inside(px[it], py[it], xs, ys) } * 2 > px.size
        fun centreInside(box: Box) = inside(box.centerX, box.centerY, xs, ys)
        val strokes = content.strokes.filter {
            val type = if (it.pen == Pen.HIGHLIGHTER) LassoType.HIGHLIGHTER else LassoType.PEN
            type in types && mostlyInside(it.x, it.y)
        }
        val objects = content.objects.filter {
            val type = when (it) {
                is Shape -> LassoType.SHAPE
                is TextBox -> LassoType.TEXT
                is Picture -> LassoType.IMAGE
            }
            type in types && centreInside(bounds(it))
        }
        val notes = if (LassoType.NOTE in types) content.notes.filter { centreInside(bounds(it)) } else emptyList()
        val masks = if (LassoType.MASK in types) {
            content.masks.filter { if (it.kind == MaskKind.PEN) mostlyInside(it.xs, it.ys) else centreInside(bounds(it)) }
        } else {
            emptyList()
        }
        return Picked(strokes.map { it.id }.toSet(), objects.map { it.id }.toSet(), notes.map { it.id }.toSet(), masks.map { it.id }.toSet())
    }

    /** The topmost note or object under the point, for touch-and-hold selection. */
    fun hit(content: PageContent, x: Float, y: Float, slop: Float): Picked? {
        content.notes.lastOrNull { bounds(it).inflate(slop).contains(x, y) }?.let { return Picked(notes = setOf(it.id)) }
        content.objects.lastOrNull { o ->
            val (xs, ys) = outline(o)
            val area = o !is Shape || o.kind.closed
            if (area && xs.size >= 3) inside(x, y, xs, ys) else bounds(o).inflate(slop).contains(x, y)
        }?.let { return Picked(objects = setOf(it.id)) }
        return null
    }
}
