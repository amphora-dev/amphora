package app.amphora.core.engine

import android.content.Context
import com.winlator.cmod.runtime.compat.box64.Box64Preset
import com.winlator.cmod.runtime.wine.EnvVars

/**
 * User-facing advanced runtime overrides.
 *
 * Low-level paths, sockets and loader variables remain owned by the engine.
 * The custom editor deliberately cannot replace them.
 */
object AdvancedRuntimePreferences {
    const val KEY_BOX64_PRESET = "advanced_box64_preset"
    const val KEY_DXVK_ASYNC = "advanced_dxvk_async"
    const val KEY_FRAME_RATE = "advanced_frame_rate"
    const val KEY_PRESENT_MODE = "advanced_present_mode"
    const val KEY_BCN_MODE = "advanced_bcn_mode"
    const val KEY_WINE_DEBUG = "advanced_wine_debug"
    const val KEY_CUSTOM_ENV = "advanced_custom_env"

    private val validEnvName = Regex("[A-Z_][A-Z0-9_]*")
    private val protectedVariables =
        setOf(
            "HOME",
            "USER",
            "TMPDIR",
            "PATH",
            "PREFIX",
            "LD_LIBRARY_PATH",
            "LD_PRELOAD",
            "DISPLAY",
            "WINEPREFIX",
            "ANDROID_ALSA_SERVER",
            "ANDROID_SYSVSHM_SERVER",
            "ANDROID_RESOLV_DNS",
            "VK_ICD_FILENAMES",
            "BOX64_RCFILE",
        )

    fun box64Preset(context: Context): String {
        val stored = prefs(context).getString(KEY_BOX64_PRESET, Box64Preset.PERFORMANCE)
        return when (stored) {
            Box64Preset.STABILITY,
            Box64Preset.COMPATIBILITY,
            Box64Preset.INTERMEDIATE,
            Box64Preset.PERFORMANCE,
            -> stored
            else -> Box64Preset.PERFORMANCE
        }
    }

    fun applyEnvOverrides(context: Context, env: EnvVars) {
        val prefs = prefs(context)

        if (prefs.getBoolean(KEY_DXVK_ASYNC, false)) {
            env.put("DXVK_ASYNC", "1")
            env.put("DXVK_GPLASYNCCACHE", "1")
        } else {
            env.put("DXVK_ASYNC", "0")
            env.put("DXVK_GPLASYNCCACHE", "0")
        }

        when (val rate = prefs.getString(KEY_FRAME_RATE, "off")) {
            "30", "45", "60", "90", "120" -> env.put("DXVK_FRAME_RATE", rate)
            else -> env.put("DXVK_FRAME_RATE", "0")
        }

        when (val mode = prefs.getString(KEY_PRESENT_MODE, "auto")) {
            "mailbox", "fifo", "immediate" -> {
                env.put("MESA_VK_WSI_PRESENT_MODE", mode)
                if (mode == "immediate") env.put("WRAPPER_MAX_IMAGE_COUNT", "1")
            }
        }

        when (prefs.getString(KEY_BCN_MODE, "default")) {
            "auto" -> env.put("WRAPPER_EMULATE_BCN", "3")
            "full" -> env.put("WRAPPER_EMULATE_BCN", "2")
            "none" -> env.put("WRAPPER_EMULATE_BCN", "0")
        }

        when (prefs.getString(KEY_WINE_DEBUG, "off")) {
            "errors" -> env.put("WINEDEBUG", "+err")
            "warnings" -> env.put("WINEDEBUG", "+err,+warn")
            else -> env.put("WINEDEBUG", "-all")
        }

        parseCustomEnv(prefs.getString(KEY_CUSTOM_ENV, "").orEmpty()).forEach { (key, value) ->
            env.put(key, value)
        }
    }

    fun parseCustomEnv(raw: String): Map<String, String> =
        buildMap {
            raw.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@forEach
                val key = trimmed.substring(0, separator).trim()
                val value = trimmed.substring(separator + 1).trim()
                if (validEnvName.matches(key) && key !in protectedVariables) put(key, value)
            }
        }

    fun rejectedCustomEnvNames(raw: String): List<String> =
        raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull line
                val key = line.substring(0, separator).trim()
                if (!validEnvName.matches(key) || key in protectedVariables) key else null
            }.toList()

    private fun prefs(context: Context) =
        context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
}
