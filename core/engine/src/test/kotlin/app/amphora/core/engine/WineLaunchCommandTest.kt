package app.amphora.core.engine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineLaunchCommandTest {
    @Test
    fun explorerLaunchOpensWinefileInsideWineDesktop() {
        assertEquals(
            "wine start /wait explorer /desktop=shell,1280x720 winefile.exe",
            buildWineExplorerCommand("1280x720"),
        )
    }

    @Test
    fun programLaunchKeepsQuotedWindowsPath() {
        assertEquals(
            "wine start /wait explorer /desktop=shell,1920x1080 \"C:\\My Game\\game.exe\"",
            buildWineProgramCommand("1920x1080", "C:\\My Game\\game.exe"),
        )
    }

    @Test
    fun programLaunchAppendsSafeExeArgsAfterQuotedPath() {
        assertEquals(
            "wine start /wait explorer /desktop=shell,1920x1080 \"C:\\My Game\\game.exe\" --cube vk --bench 8",
            buildWineProgramCommand(
                "1920x1080",
                "C:\\My Game\\game.exe",
                "--cube vk --bench 8",
            ),
        )
    }

    @Test
    fun programLaunchWithEmptyExeArgsMatchesNoArgsCommand() {
        assertEquals(
            buildWineProgramCommand("1920x1080", "C:\\My Game\\game.exe"),
            buildWineProgramCommand("1920x1080", "C:\\My Game\\game.exe", ""),
        )
        assertEquals(
            buildWineProgramCommand("1920x1080", "C:\\My Game\\game.exe"),
            buildWineProgramCommand("1920x1080", "C:\\My Game\\game.exe", "   "),
        )
    }

    @Test
    fun programLaunchDropsUnsafeExeArgs() {
        assertEquals(
            buildWineProgramCommand("1280x720", "C:\\game.exe"),
            buildWineProgramCommand("1280x720", "C:\\game.exe", "--cube vk; rm -rf /"),
        )
    }

    @Test
    fun executableWithSameNameAndSizeButDifferentContentIsUpdated() {
        val root = Files.createTempDirectory("stage-executable-").toFile()
        try {
            val source = root.resolve("picked/game.exe").apply {
                parentFile.mkdirs()
                writeText("new!")
            }
            val destination = root.resolve("drive_c/game.exe").apply {
                parentFile.mkdirs()
                writeText("old?")
            }

            assertEquals(source.length(), destination.length())
            assertTrue(stageExecutable(source, destination))
            assertEquals("new!", destination.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun executableCopyFailureIsReported() {
        val root = Files.createTempDirectory("stage-executable-failure-").toFile()
        try {
            val source = root.resolve("game.exe").apply { writeText("payload") }
            val invalidParent = root.resolve("not-a-directory").apply { writeText("occupied") }

            assertFalse(stageExecutable(source, invalidParent.resolve("game.exe")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun dosPathIsPassedThroughWithoutStaging() {
        assertEquals("C:\\windows\\system32\\winefile.exe", resolveWineDosPath("C:\\windows\\system32\\winefile.exe"))
        assertEquals("C:\\winefile.exe", resolveWineDosPath("C:/winefile.exe"))
        assertEquals(null, resolveWineDosPath("/data/local/tmp/winefile.exe"))
        assertEquals(null, resolveWineDosPath("C:windowssystem32winefile.exe"))
    }
}
