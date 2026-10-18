package net.mildhope.nxtes

import android.app.Application
import android.content.Context
import android.os.Build
import dalvik.system.ZipPathValidator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.MainScope
import net.mildhope.nxtes.data.LibraryStore
import net.mildhope.nxtes.edit.ContentClip
import net.mildhope.nxtes.ink.ToolState
import net.mildhope.nxtes.library.LibraryPrefs
import net.mildhope.nxtes.page.PageAssets
import net.mildhope.nxtes.page.PageBackgrounds
import net.mildhope.nxtes.page.PagePainter
import net.mildhope.nxtes.audio.AudioPlayer
import net.mildhope.nxtes.audio.AudioRecorder
import net.mildhope.nxtes.export.Exporter
import net.mildhope.nxtes.pdf.PdfEngine
import net.mildhope.nxtes.pdf.Thumbnails
import net.mildhope.nxtes.search.SearchIndex
import net.mildhope.nxtes.search.SearchIndexer
import net.mildhope.nxtes.sync.SyncManager
import net.mildhope.nxtes.viewer.ViewSettings
import java.io.File
import java.util.zip.ZipException
import kotlin.concurrent.thread

class NxtesApplication : Application() {
    /** For work that must finish even after the screen that started it closes, such as saving. */
    val scope = MainScope()
    val tools by lazy { ToolState(getSharedPreferences("tools", MODE_PRIVATE)) }
    val view by lazy { ViewSettings(getSharedPreferences("view", MODE_PRIVATE)) }
    val libraryPrefs by lazy { LibraryPrefs(getSharedPreferences("library", MODE_PRIVATE)) }

    /** Pages copied in the page navigator, waiting to be pasted. */
    var copiedPages by mutableStateOf<CopiedPages?>(null)

    /** Items copied with the lasso, waiting to be pasted. */
    var copiedContent by mutableStateOf<ContentClip?>(null)

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
    lateinit var searchIndex: SearchIndex
        private set
    lateinit var recorder: AudioRecorder
        private set
    val exporter by lazy { Exporter(this, library, backgrounds, assets) }
    val player by lazy { AudioPlayer() }
    lateinit var indexer: SearchIndexer
        private set
    lateinit var sync: SyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        allowLeadingSlashInZips()
        pdf = PdfEngine(this)
        library = LibraryStore(File(filesDir, "library"), pdf::pageSizes)
        backgrounds = PageBackgrounds(pdf, library::blobFile)
        assets = PageAssets(library::blobFile)
        thumbnails = Thumbnails(File(cacheDir, "thumbnails"), backgrounds, library, assets, lazy { PagePainter() })
        recorder = AudioRecorder(this, library, scope)
        searchIndex = SearchIndex(this)
        indexer = SearchIndexer(searchIndex, library, pdf, scope)
        sync = SyncManager(this, library, scope)
        sync.schedule()
        thread(name = "library-load") { library.load() }
    }
}

/**
 * Android 14 refuses ZIP entries whose names start with a slash, which Flexcil backups use. Nothing
 * here turns entry names into paths unchecked, so only `..` is still refused.
 */
private fun allowLeadingSlashInZips() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    ZipPathValidator.setCallback(
        object : ZipPathValidator.Callback {
            override fun onZipEntryAccess(path: String) {
                if (path.split('/', '\\').any { it == ".." }) throw ZipException("Invalid zip entry path: $path")
            }
        },
    )
}

data class CopiedPages(val documentId: String, val pageIds: List<String>)

val Context.nxtes get() = applicationContext as NxtesApplication
