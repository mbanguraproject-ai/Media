package com.media.app

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

// ============================================================================
//  LANGUAGE
//
//  Automatic by default: with nothing picked, Android resolves every string
//  against the phone's own language list (all of it, not just the first
//  entry), so a phone set to Portuguese gets Portuguese, and one set to a
//  language Via does not ship falls through the list to the next it does,
//  then to English. Change the phone's language and the app follows.
//
//  A pick in Settings overrides that for Via alone.
//    Android 13+  The pick goes to the system's own per-app language
//                 setting (LocaleManager), so it also shows, and can be
//                 changed, in Settings > Apps > Via > Language. The system
//                 restarts the screen in the new language and applies it to
//                 every part of the app, services included.
//    Android 12-  The pick is stored here and applied by each component as it
//                 starts (attachBaseContext), and the screen is recreated.
//
//  Translations live in res/values-xx/strings.xml. Every string has every
//  language; tools/check_translations.py verifies placeholders, plural forms
//  and escaping before a build ever sees them.
// ============================================================================

object AppLanguage {

    /** A language Via ships, named in itself so anyone can find their own. */
    class Option(val tag: String, val nativeName: String)

    val options = listOf(
        Option("en", "English"),
        Option("es", "Español"),
        Option("pt-BR", "Português (Brasil)"),
        Option("fr", "Français"),
        Option("de", "Deutsch"),
        Option("ru", "Русский"),
        Option("tr", "Türkçe"),
        Option("id", "Bahasa Indonesia"),
        Option("hi", "हिन्दी"),
        Option("vi", "Tiếng Việt"),
        Option("ja", "日本語")
    )

    private const val PREFS = "language"
    private const val KEY = "tag"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Locale tags compared the same way on every Android ("in" and "id" are one language). */
    private fun norm(tag: String): String = Locale.forLanguageTag(tag).toLanguageTag()

    /** The option picked in the app, or null for automatic. */
    fun picked(context: Context): Option? {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (list == null || list.isEmpty) null else list[0].toLanguageTag()
        } else {
            prefs(context).getString(KEY, null)
        } ?: return null
        val wanted = norm(tag)
        return options.firstOrNull { norm(it.tag) == wanted }
            ?: options.firstOrNull { Locale.forLanguageTag(it.tag).language == Locale.forLanguageTag(wanted).language }
    }

    /** The language the phone itself is set to, named in itself. */
    fun deviceLanguageName(): String {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION") android.content.res.Resources.getSystem().configuration.locale
        }
        return device.getDisplayLanguage(device).replaceFirstChar { it.titlecase(device) }
    }

    /** Switches Via to [option], or back to automatic for null. */
    fun pick(activity: Activity, option: Option?) {
        prefs(activity).edit().apply {
            if (option == null) remove(KEY) else putString(KEY, option.tag)
        }.commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the screen on its own once this lands.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (option == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(option.tag)
        } else {
            activity.recreate()
        }
    }

    /**
     * Android 12 and below: [base] in the picked language, for a component's
     * attachBaseContext. Android 13+ applies the pick itself, so [base] is
     * returned as it is, as it is when nothing is picked.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY, null)
        setProcessDefault(tag)
        if (tag == null) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Android 12 and below, after a configuration change the activity handles
     * itself (rotation): Android may put the process default back to the
     * phone's language, so it is set again.
     */
    fun keepProcessDefault(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        setProcessDefault(prefs(context).getString(KEY, null))
    }

    // The process-wide default, which Android 13+ sets itself for a per-app
    // language: dates and numbers format by it, and text picks its glyphs by
    // it (Japanese and Chinese draw some of the same characters differently).
    // Back to the phone's own list for automatic, or an earlier pick would
    // linger until the app restarts.
    private fun setProcessDefault(tag: String?) {
        LocaleList.setDefault(
            if (tag == null) android.content.res.Resources.getSystem().configuration.locales
            else LocaleList(Locale.forLanguageTag(tag))
        )
    }
}

/**
 * "1 song" / "12 songs", in the current language's own plural rules. The
 * count picks the form; [formatArgs] fill it, and default to the count alone.
 */
@Composable
fun plural(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String {
    // Read so a language change recomposes this.
    LocalConfiguration.current
    val args: Array<out Any> = if (formatArgs.isEmpty()) arrayOf(count) else formatArgs
    return LocalContext.current.resources.getQuantityString(id, count, *args)
}

/** [plural] outside composition. */
fun Context.plural(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String {
    val args: Array<out Any> = if (formatArgs.isEmpty()) arrayOf(count) else formatArgs
    return resources.getQuantityString(id, count, *args)
}

// ---------------------------------------------------------------------------
//  Library placeholders on screen. The library keeps "Unknown artist",
//  "Untitled" and the rest in English (MediaRepository.kt): they are compared,
//  matched and used in keys, and a language change must not break any of
//  that. These put them in the app's language where they are shown.
// ---------------------------------------------------------------------------

@Composable
fun shownArtist(name: String): String = when (name) {
    UNKNOWN_ARTIST -> stringResource(R.string.unknown_artist)
    VARIOUS_ARTISTS -> stringResource(R.string.various_artists)
    else -> name
}

@Composable
fun shownAlbum(name: String): String =
    if (name == UNKNOWN_ALBUM) stringResource(R.string.unknown_album) else name

// normalizeTitle's own format for recorder files: "Recording 3 - 23 Aug 2026".
private val RECORDING_TITLE = Regex(
    """^Recording(?: (\d+))?(?: \u2014 (\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4}))?$"""
)
private val MONTH_ABBR = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

@Composable
fun shownTitle(title: String): String {
    if (title == UNTITLED) return stringResource(R.string.untitled)
    val m = RECORDING_TITLE.matchEntire(title) ?: return title
    val seq = m.groupValues[1]
    val word = if (seq.isNotEmpty()) stringResource(R.string.recording_n, seq) else stringResource(R.string.recording)
    if (m.groupValues[2].isEmpty()) return word
    // From the resources the strings come from, so the date is always in the
    // same language as the word in front of it.
    LocalConfiguration.current
    val locale = LocalContext.current.resources.configuration.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) it.locales[0]
        else @Suppress("DEPRECATION") it.locale
    }
    val date = Calendar.getInstance().apply {
        clear()
        set(m.groupValues[4].toInt(), MONTH_ABBR.indexOf(m.groupValues[3]), m.groupValues[2].toInt())
    }.time
    return stringResource(R.string.recording_dated, word, DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date))
}

/**
 * Text decided away from the screen - in a session, a view model, a service
 * callback - and put into words on it, in the language the screen is in. A
 * finished String made there would be in whatever language that context had.
 */
data class UiText(@androidx.annotation.StringRes val id: Int, val args: List<Any> = emptyList()) {
    @Composable
    fun resolve(): String = stringResource(id, *args.toTypedArray())
}
