package net.mildhope.nxtes

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.MainScope
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.ink.ToolState
import net.mildhope.nxtes.library.LibraryPrefs
import net.mildhope.nxtes.page.PageAssets
import net.mildhope.nxtes.page.PageBackgrounds
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.pdf.PdfEngine
import net.mildhope.nxtes.pdf.Thumbnails
import java.io.File
import kotlin.concurrent.thread

class NxtesApplication : Application() {
    /** For work that must finish even after the screen that started it closes, such as saving. */
    val scope = MainScope()
    val tools by lazy { ToolState(getSharedPreferences("tools", MODE_PRIVATE)) }
    val libraryPrefs by lazy { LibraryPrefs(getSharedPreferences("library", MODE_PRIVATE)) }

    /** Pages copied in the page navigator, waiting to be pasted. */
    var copiedPages by mutableStateOf<CopiedPages?>(null)

    lateinit var pdf: PdfEngine
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var backgrounds: PageBackgrounds
        private set
    lateinit var assets: PageAssets
        private set
    lateinit var thumbnails: Thumbnails
        private set

    override fun onCreate() {
        super.onCreate()
        pdf = PdfEngine(this)
        library = LibraryStore(File(filesDir, "library"), pdf::pageSizes)
        backgrounds = PageBackgrounds(pdf, library::blobFile)
        assets = PageAssets(library::blobFile)
        thumbnails = Thumbnails(File(cacheDir, "thumbnails"), backgrounds, library, assets, lazy { PagePainter() })
        thread(name = "library-load") { library.load() }
    }
}

data class CopiedPages(val documentId: String, val pageIds: List<String>)

val Context.nxtes get() = applicationContext as NxtesApplication
