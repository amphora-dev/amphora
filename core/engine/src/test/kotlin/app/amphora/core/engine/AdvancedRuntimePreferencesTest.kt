package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedRuntimePreferencesTest {
    @Test
    fun acceptsOnlySupportedFrameRateLimits() {
        assertEquals(30, AdvancedRuntimePreferences.frameRateLimit("30"))
        assertEquals(120, AdvancedRuntimePreferences.frameRateLimit("120"))
        assertEquals(0, AdvancedRuntimePreferences.frameRateLimit("off"))
        assertEquals(0, AdvancedRuntimePreferences.frameRateLimit("144"))
        assertEquals(0, AdvancedRuntimePreferences.frameRateLimit(null))
        assertEquals(0, AdvancedRuntimePreferences.frameRateLimit("sync"))
        assertEquals(0, AdvancedRuntimePreferences.frameRateLimit("game"))
        assertEquals(0, AdvancedRuntimePreferences.frameRateLimit("uncapped"))
    }

    @Test
    fun frameRateEnvCoversEveryOption() {
        val sync = mapOf("AMPHORA_FRAME_RATE" to "sync", "DXVK_FRAME_RATE" to "0")
        assertEquals(sync, AdvancedRuntimePreferences.frameRateEnv("sync"))
        assertEquals(sync, AdvancedRuntimePreferences.frameRateEnv(null))
        assertEquals(sync, AdvancedRuntimePreferences.frameRateEnv("off"))
        assertEquals(sync, AdvancedRuntimePreferences.frameRateEnv("144"))
        assertEquals(
            mapOf("AMPHORA_FRAME_RATE" to "game", "DXVK_FRAME_RATE" to "0"),
            AdvancedRuntimePreferences.frameRateEnv("game"),
        )
        assertEquals(
            mapOf("AMPHORA_FRAME_RATE" to "uncapped", "DXVK_FRAME_RATE" to "0"),
            AdvancedRuntimePreferences.frameRateEnv("uncapped"),
        )
        assertEquals(
            mapOf("AMPHORA_FRAME_RATE" to "45", "DXVK_FRAME_RATE" to "45"),
            AdvancedRuntimePreferences.frameRateEnv("45"),
        )
    }

    @Test
    fun parsesValidCustomEnvironmentLines() {
        assertEquals(
            mapOf(
                "MESA_SHADER_CACHE_MAX_SIZE" to "2G",
                "DXVK_HUD" to "fps,frametimes",
            ),
            AdvancedRuntimePreferences.parseCustomEnv(
                """
                # graphics diagnostics
                MESA_SHADER_CACHE_MAX_SIZE = 2G
                DXVK_HUD=fps,frametimes
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun rejectsInvalidAndEngineOwnedVariables() {
        val raw =
            """
            LD_LIBRARY_PATH=/untrusted
            WINEPREFIX=/wrong
            lower_case=value
            MESA_DEBUG=context
            """.trimIndent()

        assertEquals(
            mapOf("MESA_DEBUG" to "context"),
            AdvancedRuntimePreferences.parseCustomEnv(raw),
        )
        assertEquals(
            listOf("LD_LIBRARY_PATH", "WINEPREFIX", "lower_case"),
            AdvancedRuntimePreferences.rejectedCustomEnvNames(raw),
        )
    }
}
