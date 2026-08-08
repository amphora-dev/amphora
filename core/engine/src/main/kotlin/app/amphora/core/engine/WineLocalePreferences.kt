package app.amphora.core.engine

import android.content.Context
import androidx.core.content.edit
import com.winlator.cmod.runtime.wine.LocaleEnv

enum class WineLocaleOption(
    val preferenceValue: String,
    val locale: String?,
    val label: String,
) {
    AUTO("auto", null, "Automatic (device language)"),
    JAPANESE("ja", "ja_JP.UTF-8", "Japanese"),
    SIMPLIFIED_CHINESE("zh-cn", "zh_CN.UTF-8", "Simplified Chinese"),
    TRADITIONAL_CHINESE("zh-tw", "zh_TW.UTF-8", "Traditional Chinese"),
    ENGLISH("en", "en_US.UTF-8", "English"),
    ;

    fun resolve(deviceLocale: String): String = locale ?: deviceLocale

    companion object {
        fun fromPreference(value: String?): WineLocaleOption =
            entries.firstOrNull { it.preferenceValue == value } ?: AUTO
    }
}

object WineLocalePreferences {
    const val KEY = "wine_locale"

    fun selected(context: Context): WineLocaleOption =
        WineLocaleOption.fromPreference(
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

    fun resolve(context: Context): String =
        selected(context).resolve(LocaleEnv.normalize(LocaleEnv.deriveFromDevice()))
}
