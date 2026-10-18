package net.mildhope.nxtes.data

enum class Pen(val code: Int) {
    BALLPOINT(1),
    FOUNTAIN(2),
    PENCIL(3),
    MARKER(4),
    HIGHLIGHTER(5),

    /** Draws shapes rather than ink. */
    SHAPE(6),
    ;

    /** Whether it lays down ink strokes. */
    val inks get() = code <= HIGHLIGHTER.code

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: BALLPOINT
    }
}

enum class InputTool(val code: Int) {
    UNKNOWN(0),
    MOUSE(1),
    TOUCH(2),
    STYLUS(3),
    ;

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * One handwritten stroke, in page points. [t] is milliseconds since the stroke started. The
 * optional channels are null when the device didn't report them; otherwise they match [x] in size.
 */
class InkStroke(
    val id: String,
    val pen: Pen,
    val color: Int,
    val size: Float,
    val tool: InputTool,
    val noiseSeed: Int,
    val created: Long,
    val x: FloatArray,
    val y: FloatArray,
    val t: IntArray,
    val pressure: FloatArray? = null,
    val tilt: FloatArray? = null,
    val orientation: FloatArray? = null,
) {
    val pointCount get() = x.size

    init {
        require(y.size == pointCount && t.size == pointCount) { "Point channels differ in length" }
        listOfNotNull(pressure, tilt, orientation).forEach {
            require(it.size == pointCount) { "Optional channel differs in length" }
        }
    }
}

/** The stroke moved by [t]: points mapped, size scaled and pen orientation turned with it. */
fun InkStroke.transformed(t: Affine): InkStroke {
    val fullTurn = (2 * Math.PI).toFloat()
    val turn = Math.toRadians(t.degrees.toDouble()).toFloat()
    val nx = FloatArray(pointCount)
    val ny = FloatArray(pointCount)
    for (i in 0 until pointCount) t.map(x[i], y[i]).let { (a, b) -> nx[i] = a; ny[i] = b }
    return InkStroke(
        id = id, pen = pen, color = color, size = size * t.scale, tool = tool, noiseSeed = noiseSeed, created = created,
        x = nx, y = ny, t = this.t, pressure = pressure, tilt = tilt,
        orientation = orientation?.let { o -> FloatArray(pointCount) { (o[it] + turn).mod(fullTurn) } },
    )
}

/** A copy with another color. */
fun InkStroke.recolored(color: Int) = InkStroke(
    id = id, pen = pen, color = color, size = size, tool = tool, noiseSeed = noiseSeed, created = created,
    x = x, y = y, t = t, pressure = pressure, tilt = tilt, orientation = orientation,
)
