package net.mildhope.nxtes.flexcil

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/*
 * The parts of Flexcil's backup format the importer reads, as documented in docs/flexcil-format.md.
 * Object coordinates are fractions of the displayed page width; dates are seconds since the epoch.
 */

internal val flexJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

@Serializable
internal class BackupInfo(val appName: String? = null, val version: String? = null)

@Serializable
internal class FolderInfo(
    val name: String? = null,
    val color: String? = null,
    val createDate: Double? = null,
    val modifiedDate: Double? = null,
    val favorite: Boolean = false,
)

@Serializable
internal class DocumentItemInfo(val favorite: Boolean = false)

@Serializable
internal class DocumentInfo(
    val key: String? = null,
    val name: String? = null,
    val type: Int = 0,
    val createDate: Double? = null,
    val modifiedDate: Double? = null,
)

@Serializable
internal class FRect(val x: Float = 0f, val y: Float = 0f, val width: Float = 0f, val height: Float = 0f)

@Serializable
internal class FPoint(val x: Float = 0f, val y: Float = 0f)

@Serializable
internal class AttachmentPage(val file: String = "", val index: Int = 0)

@Serializable
internal class PageEntry(val key: String = "", val frame: FRect = FRect(), val rotate: Float = 0f, val attachmentPage: AttachmentPage? = null)

@Serializable
internal class ObjectRef(val type: Int = 0, val key: String = "")

@Serializable
internal class DrawingEntry(
    val key: String = "",
    val type: Int = 1,
    val mode: Int = 1,
    val figure: Int = 0,
    val dashtype: Int = 0,
    val strokeColor: Long = 0xFF000000,
    val fillColor: Long = 0,
    val points: String = "",
    val start: FPoint = FPoint(),
    val scale: FPoint = FPoint(1f, 1f),
)

@Serializable
internal class ShapeEntry(
    val key: String = "",
    val shapeType: Int = 0,
    val points: String = "",
    val start: FPoint = FPoint(),
    val scale: FPoint = FPoint(1f, 1f),
    val controlPoints: List<FPoint> = emptyList(),
    val rotate: Double = 0.0,
    val dashtype: Int = 0,
    val strokeColor: Long = 0xFF000000,
    val fillColor: Long = 0,
)

@Serializable
internal class MarkPath(val points: String = "", val width: Double = 0.0)

@Serializable
internal class AnnotationEntry(val key: String = "", val type: Int = 11, val strokeColor: Long = 0, val text: String = "", val paths: List<MarkPath> = emptyList())

@Serializable
internal class MaskingEntry(
    val key: String = "",
    val figure: Int = 0,
    val points: String = "",
    val start: FPoint = FPoint(),
    val scale: FPoint = FPoint(1f, 1f),
    val strokeColor: Long = 0,
    val fillColor: Long = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
)

@Serializable
internal class MaskingAnnotationEntry(
    val key: String = "",
    val strokeColor: Long = 0,
    val fillColor: Long = 0,
    val paths: List<MarkPath> = emptyList(),
    val correct: Int = 0,
    val wrong: Int = 0,
)

@Serializable
internal class ImageEntry(
    val key: String = "",
    val frame: FRect = FRect(),
    val cropBox: FRect? = null,
    val freeCrops: List<FPoint> = emptyList(),
    val rotate: Double = 0.0,
)

@Serializable
internal class SpanStyle(
    @SerialName("font-size") val fontSize: Double? = null,
    @SerialName("font-family") val fontFamily: String? = null,
    @SerialName("font-weight") val fontWeight: String? = null,
    @SerialName("font-style") val fontStyle: String? = null,
    val color: String? = null,
)

@Serializable
internal class Span(val text: String = "", val style: SpanStyle? = null)

@Serializable
internal class Paragraph(val span: List<Span> = emptyList())

@Serializable
internal class Column(val p: Paragraph? = null)

@Serializable
internal class TextEntry(val key: String = "", val frame: FRect = FRect(), val rotate: Double = 0.0, val text: String = "", val columns: List<Column> = emptyList())

@Serializable
internal class StickyEntry(val key: String = "", val frame: FRect = FRect(), val text: String = "", val color: Long? = null)

@Serializable
internal class OutlineEntry(val name: String = "", val link: String? = null, val children: List<OutlineEntry> = emptyList())

@Serializable
internal class BookmarkEntry(val name: String = "", val type: Int = 0, val pageKey: String? = null, val children: List<BookmarkEntry> = emptyList())

@Serializable
internal class AudioRefs(val refs: List<String> = emptyList())

@Serializable
internal class RecordingInfo(val key: String = "", val name: String? = null, val start: Double = 0.0, val duration: Double = 0.0)

@Serializable
internal class SyncItem(val addtime: Double = 0.0, val obj: String = "")

@Serializable
internal class SyncList(val items: List<SyncItem> = emptyList())

/** A stroke's points as x, y and width triples, relative to its start; empty when unreadable. */
internal fun decodeStrokePoints(blob: String): FloatArray = decodeFloats(blob, 3)

/** A text mark's points as absolute x, y pairs. */
internal fun decodeLinePoints(blob: String): FloatArray = decodeFloats(blob, 2)

private fun decodeFloats(blob: String, perPoint: Int): FloatArray {
    val bytes = runCatching { Base64.getMimeDecoder().decode(blob) }.getOrNull() ?: return FloatArray(0)
    if (bytes.size < 4) return FloatArray(0)
    val buffer = ByteBuffer.wrap(bytes, 4, bytes.size - 4).order(ByteOrder.LITTLE_ENDIAN)
    val count = (bytes.size - 4) / (4 * perPoint) * perPoint
    return FloatArray(count) { buffer.getFloat() }
}

/** Unpacks a compressed metadata file: a little-endian length, then raw DEFLATE of UTF-8 JSON. */
internal fun inflateEnvelope(bytes: ByteArray): String {
    require(bytes.size > 8) { "Too short" }
    val padded = bytes.copyOfRange(8, bytes.size) + 0
    val text = InflaterInputStream(ByteArrayInputStream(padded), Inflater(true)).use { it.readBytes() }.toString(Charsets.UTF_8)
    return if (runCatching { flexJson.parseToJsonElement(text) }.isSuccess) text else URLDecoder.decode(text, "UTF-8")
}

/**
 * The elements of a JSON array file, tolerating what crashes leave behind: arrays written twice back
 * to back, and elements that don't parse, which are skipped.
 */
internal fun <T> decodeArray(text: String, serializer: KSerializer<T>): List<T> {
    val elements: List<JsonElement> = runCatching { flexJson.parseToJsonElement(text).jsonArray.toList() }.getOrElse {
        text.trim().removePrefix("[").removeSuffix("]").split("][").flatMap { part ->
            runCatching { flexJson.parseToJsonElement("[$part]").jsonArray.toList() }.getOrDefault(emptyList())
        }.distinctBy { element -> runCatching { element.jsonObject["key"]?.toString() }.getOrNull() ?: element.toString() }
    }
    return elements.mapNotNull { runCatching { flexJson.decodeFromJsonElement(serializer, it) }.getOrNull() }
}

/** Flexcil's folder colours by name. */
internal val FOLDER_COLORS = mapOf(
    "pink" to 0xFFFF375F,
    "red" to 0xFFFF453A,
    "orange" to 0xFFFF9F0B,
    "yellow" to 0xFFFFD60A,
    "green" to 0xFF30D158,
    "skyblue" to 0xFF64D1FF,
    "blue" to 0xFF0A84FF,
    "violet" to 0xFF5E5CE6,
    "purple" to 0xFFBF5AF2,
).mapValues { it.value.toInt() }
