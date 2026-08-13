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
                GraphicsDriverIds.LEEGAO to GraphicsDriverIds.LEEGAO,
                // Pre-rename value, still stored on devices that tried it early.
                "Mali-Leegao" to GraphicsDriverIds.LEEGAO,
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
            GraphicsDriverIds.resolveHostDriver(GraphicsDriverIds.WRAPPER, isAdreno = true, hasVendorHal = true),
        )
    }

    @Test
    fun turnipUsesAdrenotoolsOnlyOnAdreno() {
        assertEquals(
            GraphicsDriverIds.TURNIP_BALANCED,
            GraphicsDriverIds.resolveHostDriver(
                GraphicsDriverIds.TURNIP_BALANCED,
                isAdreno = true,
                hasVendorHal = true,
            ),
        )
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveHostDriver(
                GraphicsDriverIds.TURNIP_BALANCED,
                isAdreno = false,
                hasVendorHal = false,
            ),
        )
    }

    @Test
    fun aVendorHalTakesAnUntouchedNonAdrenoDefaultToLeegao() {
        for (configured in listOf(null, GraphicsDriverIds.WRAPPER, GraphicsDriverIds.TURNIP_BALANCED)) {
            assertEquals(
                "resolveEffectiveDriver($configured)",
                GraphicsDriverIds.LEEGAO,
                GraphicsDriverIds.resolveEffectiveDriver(configured, isAdreno = false, hasVendorHal = true),
            )
        }
    }

    @Test
    fun noVendorHalLeavesOneSystemDriverForHostAndGuest() {
        for (configured in listOf(GraphicsDriverIds.WRAPPER, GraphicsDriverIds.TURNIP_BALANCED)) {
            val effective =
                GraphicsDriverIds.resolveEffectiveDriver(configured, isAdreno = false, hasVendorHal = false)
            assertEquals(GraphicsDriverIds.SYSTEM, effective)
            assertEquals(
                GraphicsDriverIds.SYSTEM,
                GraphicsDriverIds.resolveHostDriver(effective, isAdreno = false, hasVendorHal = false),
            )
        }
    }

    @Test
    fun anExplicitSystemPickSurvivesAVendorHal() {
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveEffectiveDriver(
                GraphicsDriverIds.SYSTEM,
                isAdreno = false,
                hasVendorHal = true,
            ),
        )
    }

    @Test
    fun leegaoIsGuestOnlyAndFallsBackToTheWrapperOnAdreno() {
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveHostDriver(
                GraphicsDriverIds.LEEGAO,
                isAdreno = false,
                hasVendorHal = true,
            ),
        )
        assertEquals(
            GraphicsDriverIds.WRAPPER,
            GraphicsDriverIds.resolveEffectiveDriver(
                GraphicsDriverIds.LEEGAO,
                isAdreno = true,
                hasVendorHal = true,
            ),
        )
    }

    @Test
    fun adrenoPreservesGuestSelectionBeforeHostResolution() {
        assertEquals(
            GraphicsDriverIds.WRAPPER,
            GraphicsDriverIds.resolveEffectiveDriver(
                GraphicsDriverIds.WRAPPER,
                isAdreno = true,
                hasVendorHal = false,
            ),
        )
        assertEquals(
            GraphicsDriverIds.TURNIP_BALANCED,
            GraphicsDriverIds.resolveEffectiveDriver(
                GraphicsDriverIds.TURNIP_BALANCED,
                isAdreno = true,
                hasVendorHal = false,
            ),
        )
    }

    @Test
    fun systemAndUnknownDriversUseSystemVulkan() {
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveHostDriver(GraphicsDriverIds.SYSTEM, isAdreno = true, hasVendorHal = false),
        )
        assertEquals(
            GraphicsDriverIds.SYSTEM,
            GraphicsDriverIds.resolveHostDriver("unknown", isAdreno = true, hasVendorHal = false),
        )
    }

    @Test
    fun offeredDriversFollowTheHardwareTheyNeed() {
        assertEquals(
            listOf(GraphicsDriverIds.WRAPPER, GraphicsDriverIds.TURNIP_BALANCED),
            GraphicsDriverIds.availableDrivers(isAdreno = true, hasVendorHal = true),
        )
        assertEquals(
            listOf(GraphicsDriverIds.LEEGAO),
            GraphicsDriverIds.availableDrivers(isAdreno = false, hasVendorHal = true),
        )
        // No Adreno stack and no vendor HAL: emulators and software renderers.
        assertEquals(
            listOf(GraphicsDriverIds.SYSTEM),
            GraphicsDriverIds.availableDrivers(isAdreno = false, hasVendorHal = false),
        )
    }
}
