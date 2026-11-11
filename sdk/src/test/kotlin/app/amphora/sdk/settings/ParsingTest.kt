package app.amphora.sdk.settings

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    @Test
    fun `resolutions parse with any separator and spacing`() {
        assertEquals(Resolution(1280, 720), Resolution.parse("1280x720"))
        assertEquals(Resolution(1280, 720), Resolution.parse(" 1280 X 720 "))
        assertEquals(Resolution(1920, 1080), Resolution.parse("1920×1080"))
        assertEquals("1600x900", Resolution(1600, 900).toString())
    }

    @Test
    fun `resolutions must be within bounds and aligned`() {
        assertEquals(Resolution(640, 360), Resolution.parse("640x360"))
        assertEquals(Resolution(3840, 2160), Resolution.parse("3840x2160"))
        val rejected =
            listOf(
                "632x360", // too narrow
                "640x358", // too short
                "3848x2160", // too wide
                "3840x2162", // too tall
                "1284x720", // width not a multiple of 8
                "1280x721", // odd height
            )
        for (text in rejected) assertNull(text, Resolution.parse(text))
    }

    @Test
    fun `malformed resolutions do not parse`() {
        for (text in listOf("", "1280", "1280x", "x720", "1280*720", "1280x720p", "12800x720", "1280x720x2", "auto")) {
            assertNull(text, Resolution.parse(text))
        }
    }

    @Test
    fun `screen keeps a 720 height and follows the aspect ratio of the screen`() {
        assertEquals(Resolution(1280, 720), Resolution.resolve("screen", "", 1920, 1080))
        assertEquals(Resolution(1600, 720), Resolution.resolve("screen", "", 2400, 1080))
        assertEquals("orientation does not matter", Resolution(1600, 720), Resolution.resolve("screen", "", 1080, 2400))
        assertEquals(Resolution(1152, 720), Resolution.resolve("screen", "", 2560, 1600))
    }

    @Test
    fun `screen widths are multiples of 8 close to the exact width`() {
        for ((w, h) in listOf(2408 to 1080, 2340 to 1080, 2772 to 1240, 2856 to 1280, 3120 to 1440)) {
            val r = Resolution.resolve("screen", "", w, h)
            val exact = 720.0 * w / h
            assertEquals(720, r.height)
            assertEquals("${w}x$h", 0, r.width % 8)
            assertTrue("${w}x$h gave ${r.width} for $exact", abs(r.width - exact) < 8)
        }
    }

    @Test
    fun `screen widths are clamped`() {
        assertEquals("4 to 3", Resolution(960, 720), Resolution.resolve("screen", "", 1024, 768))
        assertEquals("square", Resolution(960, 720), Resolution.resolve("screen", "", 1000, 1000))
        assertEquals("very wide", Resolution(1920, 720), Resolution.resolve("screen", "", 3200, 1000))
    }

    @Test
    fun `screen without a known screen size falls back to 720p`() {
        assertEquals(Resolution(1280, 720), Resolution.resolve("screen", "", 0, 0))
        assertEquals(Resolution(1280, 720), Resolution.resolve("screen", "", 2400, 0))
    }

    @Test
    fun `custom uses the custom field when it parses`() {
        assertEquals(Resolution(1000, 600), Resolution.resolve("custom", "1000x600", 2400, 1080))
        assertEquals(Resolution(1280, 720), Resolution.resolve("custom", "", 2400, 1080))
        assertEquals(Resolution(1280, 720), Resolution.resolve("custom", "1001x600", 2400, 1080))
    }

    @Test
    fun `fixed choices parse and anything else is 720p`() {
        assertEquals(Resolution(1920, 1080), Resolution.resolve("1920x1080", "1000x600", 2400, 1080))
        assertEquals(Resolution(1280, 720), Resolution.resolve("huge", "", 2400, 1080))
        assertEquals(Resolution(1280, 720), Resolution.resolve("", "", 2400, 1080))
    }

    @Test
    fun `the custom resolution check allows empty and valid sizes only`() {
        assertNull(checkResolution(""))
        assertNull(checkResolution("1280x800"))
        assertNotNull(checkResolution("1280x801"))
        assertNotNull(checkResolution("wide"))
    }

    @Test
    fun `DLL overrides parse into lowercase names without the extension`() {
        assertEquals(emptyList<Pair<String, String>>(), parseDllOverridesOrNull(""))
        assertEquals(listOf("d3d11" to "n"), parseDllOverridesOrNull("d3d11=n"))
        assertEquals(
            listOf("a" to "n,b", "b" to "n,b", "c" to ""),
            parseDllOverridesOrNull("a,b=n,b;c="),
        )
        assertEquals(
            listOf("d3dx9_43" to "n,b", "xinput1_3" to "b", "dinput8" to "b,n"),
            parseDllOverridesOrNull(" D3DX9_43.DLL = n , b ; ; xinput1_3=b;dinput8.dll=b,n;"),
        )
    }

    @Test
    fun `malformed DLL overrides are refused as a whole`() {
        val malformed = listOf("d3d11", "=n", "d3d11=x", "d3d11=nb", "d3d11=n,n", "d3d 11=n", "a,,b=n", "d3d11=n;bad")
        for (text in malformed) {
            assertNull(text, parseDllOverridesOrNull(text))
            assertEquals(text, emptyList<Pair<String, String>>(), parseDllOverrides(text))
            assertNotNull(text, checkDllOverrides(text))
        }
        assertNull(checkDllOverrides("d3dx9_43=n,b;xinput1_3=b"))
        assertNull(checkDllOverrides(""))
    }

    @Test
    fun `arguments split on whitespace`() {
        assertEquals(emptyList<String>(), splitArgs(""))
        assertEquals(emptyList<String>(), splitArgs("   "))
        assertEquals(listOf("-a", "-b", "c"), splitArgs("  -a   -b\tc\n"))
        assertEquals(listOf("""C:\Games\x.exe"""), splitArgs("""C:\Games\x.exe"""))
    }

    @Test
    fun `quotes group arguments and can be empty`() {
        assertEquals(listOf("-a", "b c", "d \"e\""), splitArgs("""-a "b c" 'd "e"'"""))
        assertEquals(listOf("xy zw"), splitArgs("""x"y z"w"""))
        assertEquals(listOf(""), splitArgs("\"\""))
        assertEquals(listOf("", "a"), splitArgs("'' a"))
    }

    @Test
    fun `unbalanced quotes are refused`() {
        assertNull(splitArgs("\"unterminated"))
        assertNull(splitArgs("-a 'b"))
        assertNull("a quote of the other kind does not close", splitArgs("""'a" b"""))
    }

    @Test
    fun `environment lines parse, skipping blanks and comments`() {
        assertEquals(emptyList<Pair<String, String>>(), parseEnvOrNull(""))
        assertEquals(
            listOf("A" to "1", "B" to "", "OPTS" to "-a -b=c", "_x9" to "y"),
            parseEnvOrNull("A=1\n\n  # comment\nB=\r\nOPTS=-a -b=c\n_x9=y"),
        )
    }

    @Test
    fun `malformed environment lines are refused as a whole`() {
        for (text in listOf("1A=x", "A-B=x", "=x", "JUSTNAME", "A B=x", "A=1\nbad")) {
            assertNull(text, parseEnvOrNull(text))
            assertEquals(text, emptyList<Pair<String, String>>(), parseEnv(text))
            assertNotNull(text, checkEnv(text))
        }
        assertNull(checkEnv("DXVK_HUD=fps\n"))
    }
}
