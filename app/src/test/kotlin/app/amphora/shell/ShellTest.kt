package app.amphora.shell

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellTest {
    @Test
    fun `plain words are not quoted`() {
        assertEquals("WINEDEBUG=-all", shellQuote("WINEDEBUG=-all"))
        assertEquals("/data/user/0/app.amphora/files", shellQuote("/data/user/0/app.amphora/files"))
    }

    @Test
    fun `anything else is single-quoted, embedded quotes escaped`() {
        assertEquals("''", shellQuote(""))
        assertEquals("'C:\\Program Files\\Game'", shellQuote("C:\\Program Files\\Game"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("'\$HOME'", shellQuote("\$HOME"))
    }

    @Test
    fun `columns fill the width, never fewer than one`() {
        assertEquals(1, columnsFor(100.dp, 168.dp, 12.dp))
        assertEquals(2, columnsFor(348.dp, 168.dp, 12.dp))
        assertEquals(1, columnsFor(347.dp, 168.dp, 12.dp))
        assertEquals(5, columnsFor(1064.dp, 168.dp, 12.dp))
    }

    @Test
    fun `adb paths use sdcard and end with a slash`() {
        assertEquals(
            "/sdcard/Android/data/app.amphora/files/",
            adbPath(File("/storage/emulated/0/Android/data/app.amphora/files")),
        )
        assertEquals("/storage/1234-ABCD/Games/", adbPath(File("/storage/1234-ABCD/Games")))
    }
}
