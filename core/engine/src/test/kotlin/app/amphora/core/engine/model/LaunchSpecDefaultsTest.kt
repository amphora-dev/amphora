package app.amphora.core.engine.model

import app.amphora.core.container.model.ContainerId
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchSpecDefaultsTest {
    @Test
    fun launchDefaultsToProgramTarget() {
        val spec =
            LaunchSpec(
                exePath = "C:\\games\\demo.exe",
                containerId = ContainerId("1"),
                displaySize = DisplaySize(1280, 720),
            )
        assertEquals(LaunchTarget.PROGRAM, spec.target)
        assertEquals("", spec.exeArgs)
    }
}
