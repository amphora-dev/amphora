package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphicsDriverIdsTest {
    @Test
    fun normalizePreservesSupportedSelectionsAndFallsBackToWrapper() {
        val cases =
            mapOf(
                null to GraphicsDriverIds.WRAPPER,
                "" to GraphicsDriverIds.WRAPPER,
                GraphicsDriverIds.WRAPPER to GraphicsDriverIds.WRAPPER,
                "system" to GraphicsDriverIds.SYSTEM,
                GraphicsDriverIds.SYSTEM to GraphicsDriverIds.SYSTEM,
                GraphicsDriverIds.TURNIP_BALANCED to GraphicsDriverIds.TURNIP_BALANCED,
                "unknown-driver" to GraphicsDriverIds.WRAPPER,
            )

        cases.forEach { (input, expected) ->
            assertEquals("normalize($input)", expected, GraphicsDriverIds.normalize(input))
        }
    }

    @Test
    fun wrapperUsesSystemVulkanForHostCompositor() {
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveHostDriver(GraphicsDriverIds.WRAPPER, isAdreno = true),
        )
    }

    @Test
    fun turnipUsesAdrenotoolsOnlyOnAdreno() {
        assertEquals(
            GraphicsDriverIds.TURNIP_BALANCED,
            GraphicsDriverIds.resolveHostDriver(GraphicsDriverIds.TURNIP_BALANCED, isAdreno = true),
        )
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveHostDriver(GraphicsDriverIds.TURNIP_BALANCED, isAdreno = false),
        )
    }

    @Test
    fun systemAndUnknownDriversUseSystemVulkan() {
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveHostDriver(GraphicsDriverIds.SYSTEM, isAdreno = true),
        )
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveHostDriver("unknown", isAdreno = true),
        )
    }
}
