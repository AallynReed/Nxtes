package net.mildhope.nxtes.data

import kotlinx.serialization.Serializable

/** Ruling on a generated page. The COLUMNS patterns split the page in two. */
@Serializable
enum class Pattern { PLAIN, LINED, DOTTED, GRAPH, LEGAL, CORNELL, VERTICAL, COLUMNS, COLUMNS_LINED, COLUMNS_DOTTED, COLUMNS_GRAPH }

@Serializable
enum class Paper { WHITE, YELLOW, DARK }

/** A page background that Nxtes draws itself. */
@Serializable
data class Background(val pattern: Pattern = Pattern.PLAIN, val paper: Paper = Paper.WHITE)

/** Portrait sizes in points. */
@Serializable
enum class PaperSize(val width: Float, val height: Float) {
    A4(595.28f, 841.89f),
    A5(419.53f, 595.28f),
    B5(498.9f, 708.66f),
    LETTER(612f, 792f),
}

/** A template picked by the user: a generated background at a paper size, or a custom template. */
@Serializable
data class TemplateChoice(
    val background: Background = Background(),
    val size: PaperSize = PaperSize.A4,
    val landscape: Boolean = false,
    val customId: String? = null,
) {
    val width get() = if (landscape) size.height else size.width
    val height get() = if (landscape) size.width else size.height

    companion object {
        /** The choice that would produce [template], falling back to [default] for what it can't express. */
        fun of(template: PageTemplate?, default: TemplateChoice): TemplateChoice {
            val background = template?.background ?: return default
            val landscape = template.width > template.height
            val portraitWidth = minOf(template.width, template.height)
            val size = PaperSize.entries.minBy { kotlin.math.abs(it.width - portraitWidth) }
            return TemplateChoice(background, size, landscape)
        }
    }
}

/** What new pages in a document copy: a size and either a generated background or a PDF page. */
@Serializable
data class PageTemplate(
    val width: Float,
    val height: Float,
    val background: Background? = null,
    val pdf: PdfSource? = null,
)

/** A font file the user added; [blob] is its SHA-256 in the fonts folder. */
@Serializable
data class FontFile(val blob: String, val name: String)

/** A template made from the user's image or PDF; its page is a blob in the templates folder. */
@Serializable
data class CustomTemplate(
    val id: String,
    val name: String,
    val blob: String,
    val width: Float,
    val height: Float,
    val created: Long,
)
