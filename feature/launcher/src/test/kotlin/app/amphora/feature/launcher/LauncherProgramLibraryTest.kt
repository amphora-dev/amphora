package app.amphora.feature.launcher

import android.net.Uri
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
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
    fun `stage uses the provider leaf name and copies inside the program directory`() {
        val directory = temporaryFolder.newFolder("programs")
        val library =
            library(
                directory,
                displayName = "../../folder\\game.exe",
                input = ByteArrayInputStream("program".toByteArray()),
            )

        val staged = File(library.stage(uri))

        assertEquals(directory.canonicalFile, staged.parentFile)
        assertEquals("game.exe", staged.name)
        assertEquals("program", staged.readText())
    }

    @Test
    fun `stage falls back to game exe when display name is unavailable`() {
        val directory = temporaryFolder.newFolder("programs")
        val library =
            library(
                directory,
                displayName = null,
                input = ByteArrayInputStream(byteArrayOf(1, 2)),
            )

        val staged = File(library.stage(uri))

        assertEquals("game.exe", staged.name)
        assertTrue(staged.readBytes().contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun `stage reports a null input stream without creating a file`() {
        val directory = temporaryFolder.newFolder("programs")
        val library = library(directory, displayName = "game.exe", input = null)

        val error = assertThrows(IOException::class.java) { library.stage(uri) }

        assertTrue(error.message.orEmpty().startsWith("Cannot open picked file:"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `stage accepts an empty non-exe document like the existing flow`() {
        val directory = temporaryFolder.newFolder("programs")
        val library =
            library(
                directory,
                displayName = "setup.bin",
                input = ByteArrayInputStream(byteArrayOf()),
            )

        val staged = File(library.stage(uri))

        assertEquals("setup.bin", staged.name)
        assertEquals(0L, staged.length())
    }

    @Test
    fun `stage leaves an existing destination intact when copying fails`() {
        val directory = temporaryFolder.newFolder("programs")
        val existing = File(directory, "game.exe").apply { writeText("old") }
        val library = library(directory, displayName = "game.exe", input = FailingInputStream())

        assertThrows(IOException::class.java) { library.stage(uri) }

        assertEquals("old", existing.readText())
        assertEquals(listOf("game.exe"), directory.list().orEmpty().sorted())
    }

    @Test
    fun `listRecent returns only direct regular executables ordered by mtime`() {
        val directory = temporaryFolder.newFolder("programs")
        val older = File(directory, "older.EXE").apply { writeText("old") }
        val newer = File(directory, "newer.exe").apply { writeText("new") }
        File(directory, "notes.txt").writeText("ignore")
        File(directory, "folder.exe").mkdir()
        val outside = temporaryFolder.newFile("outside.exe")
        Files.createSymbolicLink(File(directory, "linked.exe").toPath(), outside.toPath())
        assertTrue(older.setLastModified(1_000L))
        assertTrue(newer.setLastModified(2_000L))

        val programs = library(directory).listRecent()

        assertEquals(listOf("newer.exe", "older.EXE"), programs.map(RecentProgram::name))
        assertEquals(listOf(2_000L, 1_000L), programs.map(RecentProgram::lastUsedAt))
    }

    @Test
    fun `markLaunched updates a direct program and rejects an outside path`() {
        val directory = temporaryFolder.newFolder("programs")
        val program = File(directory, "game.exe").apply {
            writeText("game")
            assertTrue(setLastModified(1_000L))
        }
        val outside = temporaryFolder.newFile("outside.exe")
        val library = library(directory)

        library.markLaunched(program.absolutePath)

        assertTrue(program.lastModified() > 1_000L)
        assertThrows(IOException::class.java) { library.markLaunched(outside.absolutePath) }
    }

    private fun library(directory: File, displayName: String? = null, input: InputStream? = null) =
        LauncherProgramLibrary(
            directory,
            FakeUris(displayName, input),
        )

    private class FakeUris(private val displayName: String?, private val input: InputStream?) : LauncherProgramUris {
        override fun displayName(uri: Uri): String? = displayName

        override fun openInputStream(uri: Uri): InputStream? = input
    }

    private class FailingInputStream : InputStream() {
        private var firstRead = true

        override fun read(): Int = if (firstRead) {
            firstRead = false
            1
        } else {
            throw IOException("copy failed")
        }
    }
}
