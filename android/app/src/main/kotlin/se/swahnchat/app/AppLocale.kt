package se.swahnchat.app

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import se.swahnchat.app.data.AppLanguage
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.PREFS_FILE
import se.swahnchat.app.data.PREF_LANGUAGE
import java.util.Locale

/**
 * The language chosen in the app (Systemets / Svenska / English).
 *
 * Android 13+: the system does it. `LocaleManager.setApplicationLocales` changes the app's language without a restart (the
 * system recreates the activities) and is the same setting as in the phone's "App languages" page. Android 12 and older: the
 * choice is kept in [AppPrefs] and put on top of the base context of the application and of every activity ([wrap]); the
 * visible activity is recreated. This is the smaller change than switching all activities to AppCompat (new dependency, an
 * AppCompat theme, three base classes) which does the same by the same means below Android 13.
 */
object AppLocale {
    /** The context to use as base context: below Android 13 with the chosen language on top; otherwise [base] unchanged. */
    fun wrap(base: Context): Context = wrap(base, Build.VERSION.SDK_INT)

    /** [sdk] is a parameter so that the Android 12 path can be tested on a newer test platform. */
    internal fun wrap(base: Context, sdk: Int): Context {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) return base
        val language = savedLanguage(base)
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** What is chosen now (on Android 13+ also when it was changed in the phone's settings). */
    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales }.getOrNull()
            if (locales != null) return if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.fromTag(locales[0].toLanguageTag())
        }
        return savedLanguage(context)
    }

    /** Applies [language] now and remembers it. [activity] is recreated where the system does not do it itself. */
    fun set(activity: Activity, prefs: AppPrefs, language: AppLanguage) {
        prefs.language = language
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        } else {
            updateApplication(activity.applicationContext, language)
            activity.recreate()
        }
    }

    /** Strings read through the application context (notifications) follow the choice from here on, not only after the next start. */
    @Suppress("DEPRECATION") // updateConfiguration is the only way to change an existing application Resources
    private fun updateApplication(app: Context, language: AppLanguage) {
        val config = Configuration(app.resources.configuration)
        val locale = if (language == AppLanguage.SYSTEM) Locale.getDefault() else Locale.forLanguageTag(language.tag)
        config.setLocales(LocaleList(locale))
        Locale.setDefault(locale)
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }

    /** Read straight from the preference file: this runs in `attachBaseContext`, before Hilt exists. Never throws. */
    private fun savedLanguage(context: Context): AppLanguage = runCatching {
        AppLanguage.valueOf(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getString(PREF_LANGUAGE, null).orEmpty())
    }.getOrDefault(AppLanguage.SYSTEM)
}
