package app.amphora.feature.launcher

import android.net.Uri
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LauncherProgramLibraryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val uri = mockk<Uri>(relaxed = true)

    @Test
    fun `stageExe confines and sanitizes a provider filename`() {
        val directory = temporaryFolder.newFolder("programs")
        val boundary =
            FakeBoundary(
                programDirectory = directory,
                displayName = "../../folder\\CON.exe",
                input = ByteArrayInputStream("portable executable".toByteArray()),
            )
        var timestamp: Long? = null
        val library =
            LauncherProgramLibrary(
                boundary = boundary,
                nowMillis = { 4_242L },
                updateTimestamp = { file, value ->
                    timestamp = value
                    file.setLastModified(value)
                },
            )

        val stagedPath = library.stageExe(uri)

        val staged = File(stagedPath)
        assertEquals(directory.canonicalFile, staged.parentFile)
        assertEquals("_CON.exe", staged.name)
        assertEquals("portable executable", staged.readText())
        assertEquals(4_242L, timestamp)
        assertFalse(File(temporaryFolder.root, "CON.exe").exists())
    }

    @Test
    fun `stageExe rejects a provider filename without an exe extension`() {
        val directory = temporaryFolder.newFolder("programs")
        val library =
            LauncherProgramLibrary(
                FakeBoundary(
                    programDirectory = directory,
                    displayName = "installer.zip",
                    input = ByteArrayInputStream(byteArrayOf(1)),
                ),
            )

        val error = assertThrows(IOException::class.java) { library.stageExe(uri) }

        assertEquals("Picked file name must end in .exe: installer.zip", error.message)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `stageExe reports a null input stream without creating a file`() {
        val directory = temporaryFolder.newFolder("programs")
        val library =
            LauncherProgramLibrary(
                FakeBoundary(
                    programDirectory = directory,
                    displayName = "game.exe",
                    input = null,
                ),
            )

        val error = assertThrows(IOException::class.java) { library.stageExe(uri) }

        assertTrue(error.message.orEmpty().startsWith("Cannot open picked file:"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `stageExe rejects an empty stream without replacing an existing program`() {
        val directory = temporaryFolder.newFolder("programs")
        val existing = File(directory, "game.exe").apply { writeText("old") }
        val library =
            LauncherProgramLibrary(
                FakeBoundary(
                    programDirectory = directory,
                    displayName = "game.exe",
                    input = ByteArrayInputStream(byteArrayOf()),
                ),
            )

        val error = assertThrows(IOException::class.java) { library.stageExe(uri) }

        assertTrue(error.message.orEmpty().startsWith("Picked file is empty:"))
        assertEquals("old", existing.readText())
        assertEquals(listOf("game.exe"), directory.list().orEmpty().sorted())
    }

    @Test
    fun `stageExe keeps an existing program when the timestamp update fails`() {
        val directory = temporaryFolder.newFolder("programs")
        val existing = File(directory, "game.exe").apply { writeText("old") }
        val library =
            LauncherProgramLibrary(
                boundary =
                FakeBoundary(
                    programDirectory = directory,
                    displayName = "game.exe",
                    input = ByteArrayInputStream("new".toByteArray()),
                ),
                updateTimestamp = { _, _ -> false },
            )

        val error = assertThrows(IOException::class.java) { library.stageExe(uri) }

        assertEquals("Cannot update program timestamp: ${existing.absolutePath}", error.message)
        assertEquals("old", existing.readText())
        assertEquals(listOf("game.exe"), directory.list().orEmpty().sorted())
    }

    @Test
    fun `scanRecentPrograms returns only direct regular executables newest first`() {
        val directory = temporaryFolder.newFolder("programs")
        val older = File(directory, "older.EXE").apply { writeText("old") }
        val newer = File(directory, "newer.exe").apply { writeText("new") }
        File(directory, "notes.txt").writeText("ignore")
        File(directory, "folder.exe").mkdir()
        val outside = temporaryFolder.newFile("outside.exe")
        Files.createSymbolicLink(File(directory, "linked.exe").toPath(), outside.toPath())
        assertTrue(older.setLastModified(1_000L))
        assertTrue(newer.setLastModified(2_000L))
        val library = LauncherProgramLibrary(FakeBoundary(programDirectory = directory))

        val programs = library.scanRecentPrograms()

        assertEquals(listOf("newer.exe", "older.EXE"), programs.map(RecentProgram::name))
        assertEquals(listOf(2_000L, 1_000L), programs.map(RecentProgram::lastUsedAt))
    }

    @Test
    fun `markProgramLaunched updates a managed executable`() {
        val directory = temporaryFolder.newFolder("programs")
        val program = File(directory, "game.exe").apply { writeText("game") }
        var updated: Pair<File, Long>? = null
        val library =
            LauncherProgramLibrary(
                boundary = FakeBoundary(programDirectory = directory),
                nowMillis = { 9_999L },
                updateTimestamp = { file, value ->
                    updated = file to value
                    true
                },
            )

        library.markProgramLaunched(program.absolutePath)

        assertEquals(program.canonicalFile to 9_999L, updated)
    }

    @Test
    fun `markProgramLaunched rejects a path outside the managed directory`() {
        val directory = temporaryFolder.newFolder("programs")
        val outside = temporaryFolder.newFile("outside.exe")
        val library = LauncherProgramLibrary(FakeBoundary(programDirectory = directory))

        val error =
            assertThrows(IOException::class.java) {
                library.markProgramLaunched(outside.absolutePath)
            }

        assertEquals(
            "Program path is outside the managed directory: ${outside.absolutePath}",
            error.message,
        )
    }

    @Test
    fun `markProgramLaunched reports timestamp failure`() {
        val directory = temporaryFolder.newFolder("programs")
        val program = File(directory, "game.exe").apply { writeText("game") }
        val library =
            LauncherProgramLibrary(
                boundary = FakeBoundary(programDirectory = directory),
                updateTimestamp = { _, _ -> false },
            )

        val error =
            assertThrows(IOException::class.java) {
                library.markProgramLaunched(program.absolutePath)
            }

        assertEquals("Cannot update program timestamp: ${program.absolutePath}", error.message)
    }

    @Test
    fun `readAppVersion preserves a version and normalizes blank metadata`() {
        val directory = temporaryFolder.newFolder("programs")

        assertEquals(
            "2.4.0",
            LauncherProgramLibrary(
                FakeBoundary(programDirectory = directory, appVersion = "2.4.0"),
            ).readAppVersion(),
        )
        assertEquals(
            "unknown",
            LauncherProgramLibrary(
                FakeBoundary(programDirectory = directory, appVersion = ""),
            ).readAppVersion(),
        )
    }

    @Test
    fun `sanitizeExeFileName bounds UTF-8 length while preserving extension`() {
        val sanitized = LauncherProgramLibrary.sanitizeExeFileName("${"游".repeat(200)}.exe")

        assertTrue(sanitized.endsWith(".exe"))
        assertTrue(sanitized.toByteArray(Charsets.UTF_8).size <= 244)
    }

    private class FakeBoundary(
        override val programDirectory: File,
        private val displayName: String? = null,
        private val input: InputStream? = null,
        private val appVersion: String = "1.0",
    ) : LauncherProgramAndroidBoundary {
        override fun queryDisplayName(uri: Uri): String? = displayName

        override fun openInputStream(uri: Uri): InputStream? = input

        override fun readAppVersion(): String = appVersion
    }
}
