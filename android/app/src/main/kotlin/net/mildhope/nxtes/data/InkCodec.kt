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

/** Reads and writes page ink in the format defined by proto/nxtes/page.proto. */
@OptIn(ExperimentalSerializationApi::class)
object InkCodec {
    private const val XY_SCALE = 100f
    private const val PRESSURE_SCALE = 1000f
    private const val ANGLE_SCALE = 1000f

    @Serializable
    private class WirePage(@ProtoNumber(1) val strokes: List<WireStroke> = emptyList())

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

    fun encode(strokes: List<InkStroke>): ByteArray =
        ProtoBuf.encodeToByteArray(WirePage(strokes.map(::toWire)))

    fun decode(bytes: ByteArray): List<InkStroke> =
        ProtoBuf.decodeFromByteArray<WirePage>(bytes).strokes.map(::fromWire)

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
