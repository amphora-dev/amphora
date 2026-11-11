package app.amphora.sdk.internal.engine

import app.amphora.sdk.internal.engine.TarWriter.Companion.MODE_EXEC
import app.amphora.sdk.internal.engine.TarWriter.Companion.MODE_FILE
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TarReaderTest {
    @Test
    fun `reads directories and files with their metadata and data`() {
        val tar =
            TarWriter()
                .dir("bin/")
                .file("bin/wine", "#!wine", MODE_EXEC)
                .file("README", "hello")
                .end()
                .reader()

        val dir = tar.next()!!
        assertEquals("bin", dir.path)
        assertEquals(TarEntryType.Directory, dir.type)
        assertEquals(0L, dir.size)

        val wine = tar.next()!!
        assertEquals("bin/wine", wine.path)
        assertEquals(TarEntryType.File, wine.type)
        assertEquals(MODE_EXEC, wine.mode)
        assertEquals(6L, wine.size)
        assertTrue(wine.executable)
        assertEquals("#!wine", tar.data.readBytes().decodeToString())

        val readme = tar.next()!!
        assertEquals("README", readme.path)
        assertEquals(MODE_FILE, readme.mode)
        assertFalse(readme.executable)
        assertEquals("hello", tar.data.readBytes().decodeToString())

        assertNull(tar.next())
    }

    @Test
    fun `a NUL type flag is a regular file`() {
        val tar = TarWriter().entry("old-style", type = '\u0000', content = "x".encodeToByteArray()).end().reader()
        val entry = tar.next()!!
        assertEquals(TarEntryType.File, entry.type)
        assertEquals("x", tar.data.readBytes().decodeToString())
    }

    @Test
    fun `block-sized, multi-block and empty data round-trip`() {
        val exact = ByteArray(TarWriter.BLOCK) { 7 }
        val large = ByteArray(10_000) { (it * 31).toByte() }
        val tar = TarWriter().file("exact", exact).file("large", large).file("empty", ByteArray(0)).end().reader()

        assertEquals(512L, tar.next()!!.size)
        assertArrayEquals(exact, tar.data.readBytes())
        assertEquals(10_000L, tar.next()!!.size)
        assertArrayEquals(large, tar.data.readBytes())
        assertEquals(0L, tar.next()!!.size)
        assertEquals(-1, tar.data.read())
        assertNull(tar.next())
    }

    @Test
    fun `unread and partly read data is skipped`() {
        val big = ByteArray(1500) { (it % 251).toByte() }
        val tar =
            TarWriter()
                .file("skipped", big)
                .file("partly", big)
                .file("last", "end")
                .end()
                .reader()

        assertEquals("skipped", tar.next()!!.path)
        assertEquals("partly", tar.next()!!.path)
        val head = ByteArray(10)
        assertEquals(10, tar.data.read(head))
        assertArrayEquals(big.copyOf(10), head)
        assertEquals("last", tar.next()!!.path)
        assertEquals("end", tar.data.readBytes().decodeToString())
        assertNull(tar.next())
    }

    @Test
    fun `reading past the end of an entry returns -1 without consuming the next header`() {
        val tar = TarWriter().file("a", "xy").file("b", "z").end().reader()
        tar.next()
        assertEquals('x'.code, tar.data.read())
        assertEquals('y'.code, tar.data.read())
        assertEquals(-1, tar.data.read())
        assertEquals(-1, tar.data.read(ByteArray(8), 0, 8))
        assertEquals("b", tar.next()!!.path)
        assertEquals("z", tar.data.readBytes().decodeToString())
    }

    @Test
    fun `short reads from the underlying stream are handled`() {
        val content = ByteArray(2000) { it.toByte() }
        val bytes = TarWriter().file("a", content).file("b", content).file("c", "tail").end().bytes()
        val tar = TarReader(Trickle(ByteArrayInputStream(bytes)))

        assertEquals("a", tar.next()!!.path)
        assertArrayEquals(content, tar.data.readBytes())
        assertEquals("b", tar.next()!!.path)
        assertEquals("c", tar.next()!!.path)
        assertEquals("tail", tar.data.readBytes().decodeToString())
        assertNull(tar.next())
    }

    @Test
    fun `the ustar prefix field extends long paths`() {
        val prefix = "drive_c/Program Files/" + "d".repeat(100)
        val tar = TarWriter().entry("file.txt", prefix = prefix, content = "x".encodeToByteArray()).end().reader()
        assertEquals("$prefix/file.txt", tar.next()!!.path)
    }

    @Test
    fun `paths longer than 255 characters are rejected`() {
        val tar = TarWriter().entry("n".repeat(100), prefix = "p".repeat(155)).end().reader()
        val e = assertThrows(TarFormatException::class.java) { tar.next() }
        assertTrue(e.message!!.endsWith("path too long"))
    }

    @Test
    fun `names are decoded as UTF-8`() {
        val tar = TarWriter().file("存档/セーブ.dat", "x").end().reader()
        assertEquals("存档/セーブ.dat", tar.next()!!.path)
    }

    @Test
    fun `the GNU ustar magic is accepted`() {
        val tar =
            TarWriter()
                .entry("a", content = "x".encodeToByteArray()) { TarWriter.put(it, 257, 8, "ustar  ") }
                .end()
                .reader()
        assertEquals("a", tar.next()!!.path)
    }

    @Test
    fun `links, devices and extension headers are rejected`() {
        for (flag in listOf('1', '2', '3', '4', '6', 'x', 'g', 'L', 'K')) {
            val tar = TarWriter().entry("entry", type = flag).end().reader()
            val e = assertThrows(TarFormatException::class.java) { tar.next() }
            assertEquals("entry: unsupported entry type '$flag'", e.message)
        }
    }

    @Test
    fun `a header without the ustar magic is rejected`() {
        val tar = TarWriter().entry("a") { TarWriter.put(it, 257, 8, "") }.end().reader()
        val e = assertThrows(TarFormatException::class.java) { tar.next() }
        assertTrue(e.message!!.startsWith("not a ustar header"))
    }

    @Test
    fun `a corrupted header fails the checksum`() {
        val header = TarWriter.header("a", '0', MODE_FILE, 0)
        header[0] = 'b'.code.toByte()
        val tar = TarWriter().raw(header).end().reader()
        val e = assertThrows(TarFormatException::class.java) { tar.next() }
        assertEquals("header checksum mismatch near 'b'", e.message)
    }

    @Test
    fun `entries that would escape or confuse the target directory are rejected`() {
        val files =
            listOf(
                "../evil",
                "a/../../evil",
                "a/..",
                "..",
                ".",
                "./a",
                "a/./b",
                "/etc/passwd",
                "a//b",
                "a/",
                "a\\b",
                "C:\\evil",
            )
        for (path in files) {
            val tar = TarWriter().file(path, "x").end().reader()
            assertThrows(path, TarFormatException::class.java) { tar.next() }
        }
        for (path in listOf("../", "/", "/tmp/", "a/../")) {
            val tar = TarWriter().dir(path).end().reader()
            assertThrows(path, TarFormatException::class.java) { tar.next() }
        }
    }

    @Test
    fun `a directory entry carrying data is rejected`() {
        val tar = TarWriter().entry("d", type = '5', content = "x".encodeToByteArray()).end().reader()
        val e = assertThrows(TarFormatException::class.java) { tar.next() }
        assertEquals("d: directory with data", e.message)
    }

    @Test
    fun `an archive without an end marker is rejected`() {
        val tar = TarWriter().file("a", "x").reader()
        assertEquals("a", tar.next()!!.path)
        val e = assertThrows(TarFormatException::class.java) { tar.next() }
        assertEquals("archive ends without an end-of-archive marker", e.message)
    }

    @Test
    fun `an empty stream is not an archive`() {
        assertThrows(TarFormatException::class.java) { TarReader(ByteArrayInputStream(ByteArray(0))).next() }
    }

    @Test
    fun `a stream that stops inside a header is truncated`() {
        val second = TarWriter.header("b", '0', MODE_FILE, 0)
        val tar = TarWriter().file("a", "x").raw(second.copyOf(100)).reader()
        tar.next()
        val e = assertThrows(EOFException::class.java) { tar.next() }
        assertEquals("archive truncated inside a header", e.message)
    }

    @Test
    fun `a stream that stops inside data fails when the data is read`() {
        val bytes = TarWriter().file("a", ByteArray(1000)).bytes().copyOf(512 + 100)
        val tar = TarReader(ByteArrayInputStream(bytes))
        assertEquals(1000L, tar.next()!!.size)
        val e = assertThrows(EOFException::class.java) { tar.data.readBytes() }
        assertEquals("archive truncated", e.message)
    }

    @Test
    fun `a stream that stops inside data fails when the data is skipped`() {
        val bytes = TarWriter().file("a", ByteArray(1000)).bytes().copyOf(512 + 100)
        val tar = TarReader(ByteArrayInputStream(bytes))
        tar.next()
        assertThrows(EOFException::class.java) { tar.next() }
    }

    @Test
    fun `a stream that stops inside the padding is truncated`() {
        val bytes = TarWriter().file("a", "x").end().bytes().copyOf(512 + 1)
        val tar = TarReader(ByteArrayInputStream(bytes))
        tar.next()
        assertEquals("x", tar.data.readBytes().decodeToString())
        assertThrows(EOFException::class.java) { tar.next() }
    }

    @Test
    fun `a single zero block at the very end is tolerated`() {
        val tar = TarWriter().file("a", "x").end(blocks = 1).reader()
        assertEquals("a", tar.next()!!.path)
        assertNull(tar.next())
    }

    @Test
    fun `nothing after the end marker is read`() {
        val tar = TarWriter().file("a", "x").end().raw(ByteArray(512) { 1 }).reader()
        tar.next()
        assertNull(tar.next())
        assertNull(tar.next())
    }

    @Test
    fun `base-256 numbers are rejected`() {
        val tar = TarWriter().entry("a") { it[124] = 0x80.toByte() }.end().reader()
        val e = assertThrows(TarFormatException::class.java) { tar.next() }
        assertEquals("a: base-256 numbers are not ustar", e.message)
    }

    @Test
    fun `numeric fields must be octal`() {
        val tar = TarWriter().entry("a") { TarWriter.put(it, 100, 8, "0000999") }.end().reader()
        val e = assertThrows(TarFormatException::class.java) { tar.next() }
        assertEquals("a: bad octal field '0000999'", e.message)
    }

    @Test
    fun `numeric fields may be space padded or empty`() {
        val tar =
            TarWriter()
                .entry("spaced", content = "abc".encodeToByteArray()) { TarWriter.put(it, 100, 8, "   755 ") }
                .entry("blank") { TarWriter.put(it, 100, 8, "") }
                .end()
                .reader()
        val spaced = tar.next()!!
        assertEquals(MODE_EXEC, spaced.mode)
        assertEquals(3L, spaced.size)
        assertEquals(0, tar.next()!!.mode)
    }

    @Test
    fun `normalize accepts safe relative paths and strips one directory slash`() {
        assertEquals("a/b/c.dll", TarReader.normalize("a/b/c.dll", TarEntryType.File))
        assertEquals("lib/wine", TarReader.normalize("lib/wine/", TarEntryType.Directory))
        assertEquals("a/.../..b/c..", TarReader.normalize("a/.../..b/c..", TarEntryType.File))
        assertEquals("x".repeat(255), TarReader.normalize("x".repeat(255), TarEntryType.File))
    }

    @Test
    fun `normalize rejects empty, overlong, trailing-slash files and NULs`() {
        val bad =
            listOf(
                "" to TarEntryType.File,
                "/" to TarEntryType.Directory,
                "x".repeat(256) to TarEntryType.File,
                "lib/" to TarEntryType.File,
                "a/b//" to TarEntryType.Directory,
                "a\u0000b" to TarEntryType.File,
            )
        for ((path, type) in bad) {
            assertThrows(path, TarFormatException::class.java) { TarReader.normalize(path, type) }
        }
    }

    @Test
    fun `any execute bit makes an entry executable`() {
        fun executable(mode: Int) = TarEntry("a", TarEntryType.File, mode, 0).executable
        assertTrue(executable(MODE_EXEC))
        assertTrue(executable(0b001_000_000))
        assertTrue(executable(0b000_001_000))
        assertTrue(executable(0b000_000_001))
        assertFalse(executable(MODE_FILE))
        assertFalse(executable(0))
    }

    private fun TarWriter.reader() = TarReader(ByteArrayInputStream(bytes()))

    /** Returns at most [chunk] bytes per read, as inflater and network streams may. */
    private class Trickle(input: InputStream, private val chunk: Int = 7) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, chunk))
    }
}
