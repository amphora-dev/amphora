package app.amphora.core.engine

import android.content.Context
import androidx.core.content.edit
import com.winlator.cmod.runtime.wine.LocaleEnv
import java.util.Locale

enum class WineLocaleOption(val preferenceValue: String, val locale: String?, val label: String) {
    AUTO("auto", null, "Automatic (device language)"),
    JAPANESE("ja", "ja_JP.UTF-8", "Japanese"),
    SIMPLIFIED_CHINESE("zh-cn", "zh_CN.UTF-8", "Simplified Chinese"),
    TRADITIONAL_CHINESE("zh-tw", "zh_TW.UTF-8", "Traditional Chinese"),
    ENGLISH("en", "en_US.UTF-8", "English"),
    ;

    fun resolve(deviceLocale: String): String =
        locale ?: deviceLocale.takeIf { languageOf(it) in SUPPORTED_WINDOWS_LANGUAGES }
            ?: ENGLISH.locale!!

    fun impact(deviceLocale: String): String {
        val resolved = resolve(deviceLocale)
        return if (this == AUTO) {
            "Automatic chose ${describe(resolved)} · ${autoReason(deviceLocale)}"
        } else {
            "$label · ANSI codepage and Windows fonts · next launch"
        }
    }

    companion object {
        private val SUPPORTED_WINDOWS_LANGUAGES =
            setOf(
                "ar", "cs", "da", "de", "el", "en", "es", "fi", "fr", "he",
                "it", "ja", "ko", "nl", "no", "pl", "pt", "ru", "sv", "th",
                "tr", "uk", "vi", "zh",
            )
        private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")

        private fun languageOf(locale: String): String =
            locale.substringBefore('.').substringBefore('_').substringBefore('-').lowercase()

        private fun regionOf(locale: String): String = locale.substringBefore('.').substringAfter('_', "").uppercase()

        fun fromPreference(value: String?): WineLocaleOption =
            entries.firstOrNull { it.preferenceValue == value } ?: AUTO

        fun describe(resolved: String): String {
            entries.firstOrNull { it != AUTO && it.locale.equals(resolved, ignoreCase = true) }
                ?.let { return "${it.label} ($resolved)" }
            val lang = languageOf(resolved)
            val region = regionOf(resolved)
            val name = when {
                lang == "zh" && region in TRADITIONAL_CHINESE_REGIONS -> "Traditional Chinese"
                lang == "zh" -> "Simplified Chinese"
                else ->
                    Locale.forLanguageTag(lang)
                        .getDisplayLanguage(Locale.ENGLISH)
                        .replaceFirstChar { ch ->
                            if (ch.isLowerCase()) ch.titlecase(Locale.ENGLISH) else ch.toString()
                        }
                        .ifBlank { resolved }
            }
            return "$name ($resolved)"
        }

        private fun autoReason(deviceLocale: String): String {
            val language = languageOf(deviceLocale)
            return if (language !in SUPPORTED_WINDOWS_LANGUAGES) {
                "this device language is not in the Windows language list"
            } else {
                "this device language is $language"
            }
        }
    }
}

object WineLocalePreferences {
    const val KEY = "wine_locale"

    fun selected(context: Context): WineLocaleOption = WineLocaleOption.fromPreference(
        context
            .getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null),
    )

    fun set(context: Context, option: WineLocaleOption) {
        context
            .getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                if (option == WineLocaleOption.AUTO) {
                    remove(KEY)
                } else {
                    putString(KEY, option.preferenceValue)
                }
            }
    }

    fun resolve(context: Context): String = selected(context).resolve(LocaleEnv.normalize(LocaleEnv.deriveFromDevice()))
}
