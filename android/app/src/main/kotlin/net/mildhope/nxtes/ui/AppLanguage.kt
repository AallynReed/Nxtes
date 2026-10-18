package net.mildhope.nxtes.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit

/**
 * The app's language: the system's, or one picked in Settings. Android 13 and later keep the pick
 * themselves (and list it in system settings); before that it's applied to each context here.
 */
object AppLanguage {
    /** The languages the app is translated into, as language tags. */
    val tags = listOf("en", "pt-PT")

    private const val PREFS = "language"
    private const val KEY = "tag"

    private fun perApp() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The picked language's tag, or null to follow the system. */
    fun current(context: Context): String? =
        if (perApp()) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().ifEmpty { null }
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }

    /** Switches to [tag], or back to the system language for null, and redraws the screen in it. */
    fun set(context: Context, tag: String?) {
        if (perApp()) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { if (tag == null) remove(KEY) else putString(KEY, tag) }
        context.findActivity()?.recreate()
    }

    /** [base] in the picked language, for Android versions that can't keep a per-app language. */
    fun wrap(base: Context): Context {
        if (perApp()) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(config)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
