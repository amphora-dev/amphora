package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphicsDriverIdsTest {
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
