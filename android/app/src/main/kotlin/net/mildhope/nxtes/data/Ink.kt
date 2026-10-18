package net.mildhope.nxtes.data

enum class Pen(val code: Int) {
    BALLPOINT(1),
    FOUNTAIN(2),
    PENCIL(3),
    MARKER(4),
    HIGHLIGHTER(5),
    ;

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

/** The stroke after its [width]x[height] page turns a quarter turn, clockwise or not. */
fun InkStroke.turned(clockwise: Boolean, width: Float, height: Float): InkStroke {
    val fullTurn = (2 * Math.PI).toFloat()
    val quarter = fullTurn / 4 * (if (clockwise) 1 else -1)
    return InkStroke(
        id = id, pen = pen, color = color, size = size, tool = tool, noiseSeed = noiseSeed, created = created,
        x = FloatArray(pointCount) { if (clockwise) height - y[it] else y[it] },
        y = FloatArray(pointCount) { if (clockwise) x[it] else width - x[it] },
        t = t, pressure = pressure, tilt = tilt,
        orientation = orientation?.let { o -> FloatArray(pointCount) { (o[it] + quarter).mod(fullTurn) } },
    )
}
