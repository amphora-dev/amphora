package app.amphora.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class WineLaunchCommandTest {
    @Test
    fun explorerLaunchOpensWineDesktopWithoutStagingAnExecutable() {
        assertEquals(
            "wine explorer /desktop=shell,1280x720",
            buildWineExplorerCommand("1280x720"),
        )
    }

    @Test
    fun programLaunchKeepsQuotedWindowsPath() {
        assertEquals(
            "wine explorer /desktop=shell,1920x1080 \"C:\\My Game\\game.exe\"",
            buildWineProgramCommand("1920x1080", "C:\\My Game\\game.exe"),
        )
    }
}
