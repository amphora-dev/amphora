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
    const val KEY_HOST_PERF_HUD = "advanced_host_perf_hud"
    const val KEY_DXVK_HUD = "advanced_dxvk_hud"
    const val KEY_SHADER_CACHE = "advanced_shader_cache"
    const val KEY_SHADER_CACHE_SIZE = "advanced_shader_cache_size"
    const val KEY_VKD3D_FEATURE_LEVEL = "advanced_vkd3d_feature_level"
    const val KEY_VKD3D_SHADER_MODEL = "advanced_vkd3d_shader_model"
    const val KEY_VKD3D_DXR = "advanced_vkd3d_dxr"
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

        if (prefs.contains(KEY_DXVK_ASYNC)) {
            val enabled = if (prefs.getBoolean(KEY_DXVK_ASYNC, true)) "1" else "0"
            env.put("DXVK_ASYNC", enabled)
            env.put("DXVK_GPLASYNCCACHE", enabled)
        }

        env.put("DXVK_FRAME_RATE", frameRateLimit(prefs.getString(KEY_FRAME_RATE, "off")).toString())

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

        when (val level = prefs.getString(KEY_VKD3D_FEATURE_LEVEL, "auto")) {
            "12_0", "12_1", "12_2", "11_0" -> env.put("VKD3D_FEATURE_LEVEL", level)
        }
        when (val shaderModel = prefs.getString(KEY_VKD3D_SHADER_MODEL, "auto")) {
            "6_0", "6_3", "6_5", "6_6", "6_7", "6_8", "6_9" ->
                env.put("VKD3D_SHADER_MODEL", shaderModel)
        }
        when (prefs.getString(KEY_VKD3D_DXR, "auto")) {
            "disabled" -> env.put("VKD3D_CONFIG", "nodxr")
            "force" -> env.put("VKD3D_CONFIG", "dxr")
            "experimental_1_2" -> env.put("VKD3D_CONFIG", "dxr12")
        }

        if (prefs.getBoolean(KEY_DXVK_HUD, false)) {
            env.put("DXVK_HUD", "fps,devinfo,api,memory,gpuload")
        }

        env.put(
            "MESA_SHADER_CACHE_DISABLE",
            if (prefs.getBoolean(KEY_SHADER_CACHE, true)) "false" else "true",
        )
        when (val size = prefs.getString(KEY_SHADER_CACHE_SIZE, "512MB")) {
            "256MB", "512MB", "1GB", "2GB" -> env.put("MESA_SHADER_CACHE_MAX_SIZE", size)
            else -> env.put("MESA_SHADER_CACHE_MAX_SIZE", "512MB")
        }

        parseCustomEnv(prefs.getString(KEY_CUSTOM_ENV, "").orEmpty()).forEach { (key, value) ->
            env.put(key, value)
        }
    }

    fun parseCustomEnv(raw: String): Map<String, String> = buildMap {
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

    fun rejectedCustomEnvNames(raw: String): List<String> = raw
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull line
            val key = line.substring(0, separator).trim()
            if (!validEnvName.matches(key) || key in protectedVariables) key else null
        }.toList()

    fun hostPerformanceHudEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_HOST_PERF_HUD, false)

    fun frameRateLimit(context: Context): Int = frameRateLimit(prefs(context).getString(KEY_FRAME_RATE, "off"))

    internal fun frameRateLimit(value: String?): Int =
        value?.toIntOrNull()?.takeIf { it in setOf(30, 45, 60, 90, 120) } ?: 0

    private fun prefs(context: Context) =
        context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
}
