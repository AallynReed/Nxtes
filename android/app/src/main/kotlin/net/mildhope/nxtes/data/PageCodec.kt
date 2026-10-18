package net.mildhope.nxtes.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked
import kotlinx.serialization.protobuf.ProtoType
import kotlin.math.roundToInt

/** Reads and writes page content in the format defined by proto/nxtes/page.proto. */
@OptIn(ExperimentalSerializationApi::class)
object PageCodec {
    private const val XY_SCALE = 100f
    private const val PRESSURE_SCALE = 1000f
    private const val ANGLE_SCALE = 1000f

    @Serializable
    private class WirePage(
        @ProtoNumber(1) val strokes: List<WireStroke> = emptyList(),
        @ProtoNumber(2) val objects: List<WireObject> = emptyList(),
        @ProtoNumber(3) val notes: List<WireNote> = emptyList(),
        @ProtoNumber(4) val masks: List<WireMask> = emptyList(),
        @ProtoNumber(5) val marks: List<WireMark> = emptyList(),
        @ProtoNumber(6) val links: List<WireLink> = emptyList(),
    )

    @Serializable
    private class WireStroke(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val pen: Int,
        @ProtoNumber(3) @ProtoType(ProtoIntegerType.FIXED) val color: Int,
        @ProtoNumber(4) val size: Float,
        @ProtoNumber(5) val tool: Int = 0,
        @ProtoNumber(6) val noiseSeed: Int = 0,
        // Already zigzag-encoded: the library doesn't apply SIGNED to packed elements.
        @ProtoNumber(7) @ProtoPacked val x: List<Int> = emptyList(),
        @ProtoNumber(8) @ProtoPacked val y: List<Int> = emptyList(),
        @ProtoNumber(9) @ProtoPacked val t: List<Int> = emptyList(),
        @ProtoNumber(10) @ProtoPacked val pressure: List<Int> = emptyList(),
        @ProtoNumber(11) @ProtoPacked val tilt: List<Int> = emptyList(),
        @ProtoNumber(12) @ProtoPacked val orientation: List<Int> = emptyList(),
        @ProtoNumber(13) val created: Long = 0,
    )

    @Serializable
    private class WireObject(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val shape: WireShape? = null,
        @ProtoNumber(3) val text: WireText? = null,
        @ProtoNumber(4) val picture: WirePicture? = null,
    )

    @Serializable
    private class WireShape(
        @ProtoNumber(1) val kind: Int,
        @ProtoNumber(2) @ProtoPacked val x: List<Float> = emptyList(),
        @ProtoNumber(3) @ProtoPacked val y: List<Float> = emptyList(),
        @ProtoNumber(4) @ProtoType(ProtoIntegerType.FIXED) val color: Int,
        @ProtoNumber(5) val width: Float,
        @ProtoNumber(6) @ProtoType(ProtoIntegerType.FIXED) val fill: Int = 0,
        @ProtoNumber(7) val line: Int = 0,
    )

    @Serializable
    private class WireText(
        @ProtoNumber(1) val text: String,
        @ProtoNumber(2) val x: Float,
        @ProtoNumber(3) val y: Float,
        @ProtoNumber(4) val width: Float,
        @ProtoNumber(5) val height: Float,
        @ProtoNumber(6) val rotation: Float = 0f,
        @ProtoNumber(7) val font: String = "",
        @ProtoNumber(8) val size: Float,
        @ProtoNumber(9) @ProtoType(ProtoIntegerType.FIXED) val color: Int,
        @ProtoNumber(10) @ProtoType(ProtoIntegerType.FIXED) val background: Int = 0,
        @ProtoNumber(11) val bold: Boolean = false,
        @ProtoNumber(12) val italic: Boolean = false,
        @ProtoNumber(13) val underline: Boolean = false,
    )

    @Serializable
    private class WirePicture(
        @ProtoNumber(1) val blob: String,
        @ProtoNumber(2) val cx: Float,
        @ProtoNumber(3) val cy: Float,
        @ProtoNumber(4) val width: Float,
        @ProtoNumber(5) val height: Float,
        @ProtoNumber(6) val rotation: Float = 0f,
        @ProtoNumber(7) val cropLeft: Float = 0f,
        @ProtoNumber(8) val cropTop: Float = 0f,
        @ProtoNumber(9) val cropRight: Float = 1f,
        @ProtoNumber(10) val cropBottom: Float = 1f,
        @ProtoNumber(11) @ProtoPacked val outline: List<Float> = emptyList(),
        @ProtoNumber(12) val sticker: Boolean = false,
    )

    @Serializable
    private class WireNote(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val x: Float,
        @ProtoNumber(3) val y: Float,
        @ProtoNumber(4) val text: String = "",
        @ProtoNumber(5) @ProtoType(ProtoIntegerType.FIXED) val color: Int,
        @ProtoNumber(6) val created: Long = 0,
    )

    @Serializable
    private class WireMask(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val kind: Int,
        @ProtoNumber(3) @ProtoPacked val x: List<Float> = emptyList(),
        @ProtoNumber(4) @ProtoPacked val y: List<Float> = emptyList(),
        @ProtoNumber(5) val width: Float = 0f,
        @ProtoNumber(6) @ProtoType(ProtoIntegerType.FIXED) val color: Int,
        @ProtoNumber(7) val tries: Int = 0,
        @ProtoNumber(8) val correct: Int = 0,
    )

    @Serializable
    private class WireMark(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val kind: Int,
        @ProtoNumber(3) @ProtoType(ProtoIntegerType.FIXED) val color: Int,
        @ProtoNumber(4) @ProtoPacked val rects: List<Float> = emptyList(),
        @ProtoNumber(5) val text: String = "",
    )

    @Serializable
    private class WireLink(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val x: Float,
        @ProtoNumber(3) val y: Float,
        @ProtoNumber(4) val width: Float,
        @ProtoNumber(5) val height: Float,
        @ProtoNumber(6) val page: String = "",
        @ProtoNumber(7) val url: String = "",
    )

    fun encode(content: PageContent): ByteArray = ProtoBuf.encodeToByteArray(
        WirePage(
            strokes = content.strokes.map(::toWire),
            objects = content.objects.map(::toWire),
            notes = content.notes.map { WireNote(it.id, it.x, it.y, it.text, it.color, it.created) },
            masks = content.masks.map { WireMask(it.id, it.kind.code, it.xs.toList(), it.ys.toList(), it.width, it.color, it.tries, it.correct) },
            marks = content.marks.map { WireMark(it.id, it.kind.code, it.color, it.rects.toList(), it.text) },
            links = content.links.map { WireLink(it.id, it.x, it.y, it.width, it.height, it.pageId.orEmpty(), it.url.orEmpty()) },
        ),
    )

    fun decode(bytes: ByteArray): PageContent {
        val wire = ProtoBuf.decodeFromByteArray<WirePage>(bytes)
        return PageContent(
            strokes = wire.strokes.map(::fromWire),
            objects = wire.objects.mapNotNull(::fromWire),
            notes = wire.notes.map { StickyNote(it.id, it.x, it.y, it.text, it.color, it.created) },
            masks = wire.masks.map { Mask(it.id, MaskKind.fromCode(it.kind), it.x.toFloatArray(), it.y.toFloatArray(), it.width, it.color, it.tries, it.correct) },
            marks = wire.marks.map { TextMark(it.id, MarkKind.fromCode(it.kind), it.color, it.rects.toFloatArray(), it.text) },
            links = wire.links.map { Link(it.id, it.x, it.y, it.width, it.height, it.page.ifEmpty { null }, it.url.ifEmpty { null }) },
        )
    }

    private fun toWire(o: PageObject) = when (o) {
        is Shape -> WireObject(o.id, shape = WireShape(o.kind.code, o.xs.toList(), o.ys.toList(), o.color, o.width, o.fill, o.line.code))
        is TextBox -> WireObject(
            o.id,
            text = WireText(o.text, o.x, o.y, o.width, o.height, o.rotation, o.font, o.size, o.color, o.background, o.bold, o.italic, o.underline),
        )
        is Picture -> WireObject(
            o.id,
            picture = WirePicture(
                o.blob, o.cx, o.cy, o.width, o.height, o.rotation,
                o.cropLeft, o.cropTop, o.cropRight, o.cropBottom, o.outline, o.sticker,
            ),
        )
    }

    /** Objects of a kind this version doesn't know are dropped. */
    private fun fromWire(w: WireObject): PageObject? {
        w.shape?.let { s ->
            return Shape(w.id, ShapeKind.fromCode(s.kind), s.x.toFloatArray(), s.y.toFloatArray(), s.color, s.width, s.fill, LineStyle.fromCode(s.line))
        }
        w.text?.let { t ->
            return TextBox(
                w.id, t.text, t.x, t.y, t.width, t.height, t.rotation, t.font.ifEmpty { "sans-serif" }, t.size, t.color,
                t.background, t.bold, t.italic, t.underline,
            )
        }
        w.picture?.let { p ->
            return Picture(
                w.id, p.blob, p.cx, p.cy, p.width, p.height, p.rotation,
                p.cropLeft, p.cropTop, p.cropRight, p.cropBottom, p.outline, p.sticker,
            )
        }
        return null
    }

    private fun toWire(s: InkStroke) = WireStroke(
        id = s.id,
        pen = s.pen.code,
        color = s.color,
        size = s.size,
        tool = s.tool.code,
        noiseSeed = s.noiseSeed,
        x = deltas(s.x, XY_SCALE),
        y = deltas(s.y, XY_SCALE),
        t = s.t.indices.map { i -> s.t[i] - (if (i == 0) 0 else s.t[i - 1]) },
        pressure = s.pressure?.map { (it * PRESSURE_SCALE).roundToInt() }.orEmpty(),
        tilt = s.tilt?.map { (it * ANGLE_SCALE).roundToInt() }.orEmpty(),
        orientation = s.orientation?.map { (it * ANGLE_SCALE).roundToInt() }.orEmpty(),
        created = s.created,
    )

    private fun fromWire(w: WireStroke): InkStroke {
        val n = w.x.size
        fun optional(values: List<Int>, scale: Float) =
            if (values.size == n && n > 0) FloatArray(n) { values[it] / scale } else null
        var time = 0
        return InkStroke(
            id = w.id,
            pen = Pen.fromCode(w.pen),
            color = w.color,
            size = w.size,
            tool = InputTool.fromCode(w.tool),
            noiseSeed = w.noiseSeed,
            created = w.created,
            x = sums(w.x, XY_SCALE),
            y = sums(w.y, XY_SCALE),
            t = IntArray(n) { i -> (time + w.t.getOrElse(i) { 0 }).also { time = it } },
            pressure = optional(w.pressure, PRESSURE_SCALE),
            tilt = optional(w.tilt, ANGLE_SCALE),
            orientation = optional(w.orientation, ANGLE_SCALE),
        )
    }

    private fun deltas(values: FloatArray, scale: Float): List<Int> {
        var previous = 0
        return values.map { v -> (v * scale).roundToInt().let { q -> zigzag(q - previous).also { previous = q } } }
    }

    private fun sums(deltas: List<Int>, scale: Float): FloatArray {
        var total = 0
        return FloatArray(deltas.size) { i -> (total + unzigzag(deltas[i])).also { total = it } / scale }
    }

    private fun zigzag(n: Int) = (n shl 1) xor (n shr 31)

    private fun unzigzag(z: Int) = (z ushr 1) xor -(z and 1)
}
