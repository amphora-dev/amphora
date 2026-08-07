package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectDrawWrapperIdsTest {
    @Test
    fun defaultsToDxWrapper() {
        assertEquals(
            DirectDrawWrapperIds.DXWRAPPER_DD7TO9,
            DirectDrawWrapperIds.normalize(null),
        )
        assertEquals(
            DirectDrawWrapperIds.DXWRAPPER_DD7TO9,
            DirectDrawWrapperIds.normalize("none"),
        )
    }

    @Test
    fun preservesSupportedChoices() {
        assertEquals(
            DirectDrawWrapperIds.DXWRAPPER_DD7TO9,
            DirectDrawWrapperIds.normalize(DirectDrawWrapperIds.DXWRAPPER_DD7TO9),
        )
        assertEquals(
            DirectDrawWrapperIds.CNC_DDRAW,
            DirectDrawWrapperIds.normalize(DirectDrawWrapperIds.CNC_DDRAW),
        )
        assertEquals(
            DirectDrawWrapperIds.D7VK,
            DirectDrawWrapperIds.normalize(DirectDrawWrapperIds.D7VK),
        )
    }
}
