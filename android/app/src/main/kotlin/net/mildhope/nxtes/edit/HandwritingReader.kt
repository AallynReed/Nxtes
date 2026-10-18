package net.mildhope.nxtes.edit

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import kotlinx.coroutines.suspendCancellableCoroutine
import net.mildhope.nxtes.data.InkStroke
import net.mildhope.nxtes.ui.AppLanguage
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads handwriting as text with ML Kit's on-device recognizer. Each language's model is fetched
 * from Google the first time it's used. Call from the main thread.
 */
class HandwritingReader(private val context: Context) {
    private val prefs = context.getSharedPreferences("handwriting", Context.MODE_PRIVATE)
    private val models = RemoteModelManager.getInstance()
    private var recognizer: Pair<String, DigitalInkRecognizer>? = null

    /** The language handwriting is read in, as a tag: the one last picked, or else the app's. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, null) ?: identifier(AppLanguage.current(context) ?: Locale.getDefault().toLanguageTag()).languageTag
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value) }

    /** Languages to offer: the current one, then the usual ones. */
    val languages get() = (listOf(language) + LANGUAGES).distinct()

    suspend fun isDownloaded(tag: String): Boolean = models.isModelDownloaded(model(tag)).await()

    /**
     * The text written in [strokes], a line of text per line of writing, downloading the language's
     * model first if needed. [before] is text that came just before the writing, to help read it.
     */
    suspend fun read(strokes: List<InkStroke>, before: String = ""): String {
        val tag = language
        val model = model(tag)
        if (!models.isModelDownloaded(model).await()) models.download(model, DownloadConditions.Builder().build()).await()
        val reader = recognizer?.takeIf { it.first == tag }?.second ?: run {
            recognizer?.second?.close()
            DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()).also { recognizer = tag to it }
        }
        val writing = strokes.filter { it.isWriting }
        val out = StringBuilder()
        for (line in writingLines(writing.map { it.bounds })) {
            val ink = Ink.builder()
            var clock = 0L
            for (s in line.sorted().map { writing[it] }.filter { it.pointCount > 0 }) {
                val start = maxOf(s.created, clock + 1)
                val stroke = Ink.Stroke.builder()
                for (i in 0 until s.pointCount) stroke.addPoint(Ink.Point.create(s.x[i], s.y[i], start + s.t[i]))
                ink.addStroke(stroke.build())
                clock = start + s.t.last()
            }
            val so = "$before\n$out".replace('\n', ' ').trim()
            val hint = if (so.isEmpty()) "" else "$so ".takeLast(PRE_CONTEXT)
            val result = reader.recognize(ink.build(), RecognitionContext.builder().setPreContext(hint).build()).await()
            val text = result.candidates.firstOrNull()?.text?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
        }
        return out.toString()
    }

    private fun model(tag: String) = DigitalInkRecognitionModel.builder(identifier(tag)).build()

    /** The recognizer for [tag], or for its language in the usual region, or else for American English. */
    private fun identifier(tag: String): DigitalInkRecognitionModelIdentifier {
        runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull()?.let { return it }
        val language = Locale.forLanguageTag(tag).language
        val candidates = runCatching { DigitalInkRecognitionModelIdentifier.forLanguageSubtag(language) }.getOrNull().orEmpty()
            .filter { !it.languageTag.contains("gesture") }
        return candidates.firstOrNull { it.languageTag in LANGUAGES } ?: candidates.minByOrNull { it.languageTag } ?: DigitalInkRecognitionModelIdentifier.EN_US
    }

    private companion object {
        const val KEY_LANGUAGE = "language"

        /** Characters of the text read so far that go along as context. */
        const val PRE_CONTEXT = 20

        val LANGUAGES = listOf("en-US", "en-GB", "pt-PT", "pt-BR", "es-ES", "fr-FR", "de-DE", "it-IT", "nl-NL")
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
