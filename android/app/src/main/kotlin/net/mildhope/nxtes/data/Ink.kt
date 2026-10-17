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
