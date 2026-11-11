package app.amphora.sdk.logs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a small file is returned whole`() {
        assertEquals(listOf("one", "two", "three"), tail("one\ntwo\nthree\n"))
        assertEquals("without a final newline", listOf("one", "two"), tail("one\ntwo"))
        assertEquals("blank lines are kept", listOf("a", "", "b"), tail("a\n\nb\n"))
    }

    @Test
    fun `only the last maxLines lines are returned`() {
        val text = (1..10).joinToString("") { "line $it\n" }
        assertEquals(listOf("line 8", "line 9", "line 10"), tail(text, maxLines = 3))
        assertEquals(10, tail(text, maxLines = 100).size)
    }

    @Test
    fun `a window that starts inside a line drops that partial line`() {
        // "first line\n" is 11 bytes, "second\n" 7, "third\n" 6: the last 12 bytes start inside "second".
        assertEquals(listOf("third"), tail("first line\nsecond\nthird\n", maxBytes = 12))
    }

    @Test
    fun `a window that starts on a line boundary keeps its first line`() {
        assertEquals(listOf("second", "third"), tail("first line\nsecond\nthird\n", maxBytes = 13))
    }

    @Test
    fun `maxLines applies after maxBytes`() {
        val text = (1..100).joinToString("") { number(it) + "\n" }
        // 40 bytes hold the last 10 lines of 4 bytes each; maxLines then keeps 3.
        assertEquals(listOf("098", "099", "100"), tail(text, maxLines = 3, maxBytes = 40))
        assertEquals((91..100).map(::number), tail(text, maxLines = 50, maxBytes = 40))
    }

    @Test
    fun `CRLF line endings are trimmed`() {
        assertEquals(listOf("a", "b"), tail("a\r\nb\r\n"))
        assertEquals(listOf("a", "b"), tail("a\r\nb"))
    }

    @Test
    fun `a cut through a multibyte character only affects the dropped line`() {
        // "日本語\n" is 10 bytes; the last 11 bytes start in the middle of "日".
        assertEquals(listOf("ok"), tail("日本語\nok\n", maxBytes = 11))
        assertEquals(listOf("日本語", "ok"), tail("日本語\nok\n"))
    }

    @Test
    fun `missing and empty files have no lines`() {
        assertEquals(emptyList<String>(), LogStore.tailOf(File(temp.root, "missing.log"), 10, 1024))
        assertEquals(emptyList<String>(), tail(""))
        assertEquals("one empty line", listOf(""), tail("\n"))
    }

    private fun number(n: Int) = n.toString().padStart(3, '0')

    private fun tail(text: String, maxLines: Int = 500, maxBytes: Int = 256 * 1024): List<String> {
        val file = temp.newFile()
        file.writeText(text)
        return LogStore.tailOf(file, maxLines, maxBytes)
    }
}
