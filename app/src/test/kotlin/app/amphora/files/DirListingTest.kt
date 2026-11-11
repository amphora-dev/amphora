package app.amphora.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirListingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `natural order compares numbers as numbers and ignores case`() {
        val names = listOf("part10", "Part2", "part1", "Setup.exe", "game.exe", "Game 9", "game 10", "a", "A")
        assertEquals(
            listOf("A", "a", "Game 9", "game 10", "game.exe", "part1", "Part2", "part10", "Setup.exe"),
            names.sortedWith(NaturalOrder),
        )
        assertTrue(NaturalOrder.compare("Part2", "part10") < 0)
        assertTrue(NaturalOrder.compare("v1.9", "v1.10") < 0)
        assertTrue(NaturalOrder.compare("file007", "file7") != 0)
        assertTrue(NaturalOrder.compare("Game 9", "game 10") < 0)
    }

    @Test
    fun `natural order is consistent with equality`() {
        assertEquals(0, NaturalOrder.compare("same", "same"))
        assertTrue(NaturalOrder.compare("a", "A") != 0)
        assertEquals(-NaturalOrder.compare("a", "A").coerceIn(-1, 1), NaturalOrder.compare("A", "a").coerceIn(-1, 1))
    }

    @Test
    fun `lists folders and executables, counts the rest`() {
        val dir = temp.newFolder("game")
        File(dir, "Binaries").mkdir()
        File(dir, "Data10").mkdir()
        File(dir, "Data2").mkdir()
        File(dir, ".git").mkdir()
        File(dir, "Game.EXE").writeBytes(ByteArray(10))
        File(dir, "launcher.exe").writeBytes(ByteArray(3))
        File(dir, "readme.txt").writeText("hi")
        File(dir, "data.pak").writeText("x")
        File(dir, ".hidden.exe").writeText("x")

        val listing = DirListing.read(dir)!!
        assertEquals(listOf("Binaries", "Data2", "Data10"), listing.folders.map { it.name })
        assertEquals(listOf("Game.EXE", "launcher.exe"), listing.exes.map { it.name })
        assertEquals(listOf(10L, 3L), listing.exes.map { it.bytes })
        assertEquals(2, listing.others)
        assertEquals(2, listing.hidden)
    }

    @Test
    fun `an unreadable directory has no listing`() {
        assertNull(DirListing.read(File(temp.root, "missing")))
    }

    @Test
    fun `the deepest root wins`() {
        val internal = StorageRoot("内部存储", File("/storage/emulated/0"), StorageRoot.Kind.Internal)
        val sideload =
            StorageRoot("侧载目录", File("/storage/emulated/0/Android/data/app.amphora/files"), StorageRoot.Kind.Sideload)
        val roots = listOf(internal, sideload)
        assertEquals(sideload, StorageRoot.of(roots, File("/storage/emulated/0/Android/data/app.amphora/files/Game")))
        assertEquals(internal, StorageRoot.of(roots, File("/storage/emulated/0/Games")))
        assertEquals(internal, StorageRoot.of(roots, File("/storage/emulated/0")))
        // A sibling that merely shares a prefix is not inside.
        assertNull(StorageRoot.of(roots, File("/storage/emulated/01")))
    }

    @Test
    fun `crumbs walk down from the root`() {
        val root = StorageRoot("内部存储", File("/storage/emulated/0"), StorageRoot.Kind.Internal)
        assertEquals(
            listOf(
                Crumb("内部存储", File("/storage/emulated/0")),
                Crumb("Games", File("/storage/emulated/0/Games")),
                Crumb("Hollow Knight", File("/storage/emulated/0/Games/Hollow Knight")),
            ),
            crumbs(root, File("/storage/emulated/0/Games/Hollow Knight")),
        )
        assertEquals(listOf(Crumb("内部存储", root.dir)), crumbs(root, root.dir))
        assertEquals(listOf(Crumb("/mnt/other", File("/mnt/other"))), crumbs(root, File("/mnt/other")))
        assertEquals(listOf(Crumb("/mnt/other", File("/mnt/other"))), crumbs(null, File("/mnt/other")))
    }
}
