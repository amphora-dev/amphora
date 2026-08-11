package app.amphora.core.engine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineLaunchCommandTest {
    @Test
    fun explorerLaunchOpensFileManagerInsideWineDesktop() {
        assertEquals(
            "wine explorer /desktop=shell,1280x720 explorer.exe",
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
}
