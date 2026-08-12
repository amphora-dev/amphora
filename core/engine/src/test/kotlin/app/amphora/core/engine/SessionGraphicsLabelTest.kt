package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGraphicsLabelTest {
    @Test
    fun describesPinnedDxvkVkd3dAndDirectDrawStack() {
        assertEquals(
            "DXVK 3.0.2 + VKD3D 2.14.1 + Dd7to9",
            SessionGraphicsLabel.fromDxWrapper(
                "dxvk-3.0.2-1;vkd3d-2.14.1-1;dd7to9",
            ),
        )
    }

    @Test
    fun keepsNamedDxvkVariantsWhileRemovingPackageRevision() {
        assertEquals(
            "DXVK 2.7.1-gplasync + D7VK",
            SessionGraphicsLabel.fromDxWrapper("dxvk-2.7.1-gplasync-0;vkd3d-None;d7vk"),
        )
    }

    @Test
    fun fallsBackToWineD3dForMissingSelection() {
        assertEquals("WineD3D / auto", SessionGraphicsLabel.fromDxWrapper(null))
        assertEquals("WineD3D", SessionGraphicsLabel.fromDxWrapper("original-wined3d"))
    }
}
