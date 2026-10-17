package net.mildhope.nxtes

import android.app.Application
import android.content.Context
import androidx.ink.brush.StockTextureBitmapStore
import kotlinx.coroutines.MainScope
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.pdf.PdfEngine
import net.mildhope.nxtes.pdf.Thumbnails
import java.io.File
import kotlin.concurrent.thread

class NxtesApplication : Application() {
    /** For work that must finish even after the screen that started it closes, such as saving. */
    val scope = MainScope()
    val textures by lazy { StockTextureBitmapStore(resources) }

    lateinit var pdf: PdfEngine
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var thumbnails: Thumbnails
        private set

    override fun onCreate() {
        super.onCreate()
        pdf = PdfEngine(this)
        library = LibraryStore(File(filesDir, "library"), pdf::pageSizes)
        thumbnails = Thumbnails(File(cacheDir, "thumbnails"), pdf, library)
        thread(name = "library-load") { library.load() }
    }
}

val Context.nxtes get() = applicationContext as NxtesApplication
