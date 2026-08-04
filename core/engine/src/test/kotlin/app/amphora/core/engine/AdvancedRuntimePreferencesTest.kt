package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedRuntimePreferencesTest {
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
