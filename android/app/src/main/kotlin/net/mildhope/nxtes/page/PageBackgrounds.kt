package net.mildhope.nxtes.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mildhope.nxtes.data.Page
import net.mildhope.nxtes.pdf.PdfEngine
import java.io.File

/** Draws what's under the ink: the PDF page, a generated background, or plain white. */
class PageBackgrounds(private val pdf: PdfEngine, private val blobFile: (documentId: String, sha256: String) -> File) {
    /** Draws [page] at [left],[top] with size [width]x[height] in bitmap pixels; offsets may be negative. */
    suspend fun render(documentId: String, page: Page, bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int) {
        val source = page.pdf
        val background = page.background
        when {
            source != null -> pdf.render(blobFile(documentId, source.blob), source.index, bitmap, left, top, width, height, page.turns)
            background != null -> withContext(Dispatchers.Default) {
                val scale = width / page.width
                Canvas(bitmap).withTranslation(left.toFloat(), top.toFloat()) {
                    scale(scale, height / page.height)
                    drawTemplate(this, page, scale)
                }
            }
            else -> bitmap.eraseColor(Color.WHITE)
        }
    }

    companion object {
        /** Draws a page's generated background in page points, turned with the page. [pxPerPt] keeps hairlines visible. */
        fun drawTemplate(canvas: Canvas, page: Page, pxPerPt: Float) {
            val background = page.background ?: return
            val sideways = page.turns % 2 == 1
            val sourceWidth = if (sideways) page.height else page.width
            val sourceHeight = if (sideways) page.width else page.height
            canvas.withSave {
                when (page.turns.mod(4)) {
                    1 -> translate(page.width, 0f)
                    2 -> translate(page.width, page.height)
                    3 -> translate(0f, page.height)
                }
                rotate(90f * page.turns.mod(4))
                TemplatePainter.draw(this, background, sourceWidth, sourceHeight, pxPerPt)
            }
        }
    }
}
