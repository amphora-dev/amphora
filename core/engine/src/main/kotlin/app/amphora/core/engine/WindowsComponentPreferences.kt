package app.amphora.core.engine

import android.content.Context
import androidx.core.content.edit

/**
 * User-selected Wine DLL source, using WinNative's `wincomponents` wire format.
 *
 * The actual DLL extraction/restoration remains owned by WinComponentSetup and
 * runs during prefix preparation when this value differs from the container's
 * last-applied snapshot.
 */
object WindowsComponentPreferences {
    const val KEY_WINCOMPONENTS = "windows_components"
    const val DEFAULT_SELECTION =
        "direct3d=1,directsound=1,directmusic=1,directshow=1,directplay=1,xaudio=1,dinput8=1,vcrun2010=1"

    val componentIds =
        listOf(
            "direct3d",
            "directsound",
            "directmusic",
            "directshow",
            "directplay",
            "xaudio",
            "dinput8",
            "vcrun2010",
        )

    fun serialized(context: Context): String = normalize(
        prefs(context).getString(KEY_WINCOMPONENTS, null)
            ?: DEFAULT_SELECTION,
    )

    fun selections(context: Context): Map<String, Boolean> = parse(serialized(context)).mapValues { it.value == "1" }

    fun setNative(context: Context, componentId: String, useNative: Boolean) {
        require(componentId in componentIds) { "Unknown Windows component: $componentId" }
        val values = parse(serialized(context)).toMutableMap()
        values[componentId] = if (useNative) "1" else "0"
        prefs(context).edit { putString(KEY_WINCOMPONENTS, serialize(values)) }
    }

    fun normalize(raw: String): String = serialize(parse(raw))

    private fun parse(raw: String): Map<String, String> {
        val provided =
            raw
                .split(',')
                .mapNotNull { token ->
                    val parts = token.trim().split('=', limit = 2)
                    if (parts.size == 2 && parts[0] in componentIds && parts[1] in setOf("0", "1")) {
                        parts[0] to parts[1]
                    } else {
                        null
                    }
                }.toMap()
        val fallback =
            DEFAULT_SELECTION
                .split(',')
                .associate { token ->
                    val parts = token.split('=', limit = 2)
                    parts[0] to parts[1]
                }
        return componentIds.associateWith { provided[it] ?: fallback.getValue(it) }
    }

    private fun serialize(values: Map<String, String>): String =
        componentIds.joinToString(",") { id -> "$id=${values[id] ?: "1"}" }

    private fun prefs(context: Context) =
        context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
}
