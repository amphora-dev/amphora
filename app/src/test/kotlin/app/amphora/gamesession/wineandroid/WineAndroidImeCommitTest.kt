package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping cases for IME commit → KEYBOARD_EVENT (no KeyCharacterMap / Robolectric).
 *
 * Unmapped code points are returned for the desktop to inject via
 * [WineAndroidNative.nativeSendUnicodeChar] (KEYEVENTF_UNICODE) on the native
 * side — not covered here (JNI / event pipe).
 */
class WineAndroidImeCommitTest {
    @Test
    fun mapsAsciiCodePointsViaLookup() {
        val (events, unmapped) =
            WineAndroidImeCommit.mapCommittedCodePoints("ab") { codePoint, chars ->
                assertEquals(1, chars.size)
                listOf("down:$codePoint", "up:$codePoint")
            }
        assertEquals(
            listOf("down:97", "up:97", "down:98", "up:98"),
            events,
        )
        assertTrue(unmapped.isEmpty())
    }

    @Test
    fun recordsUnmappedCodePointsWithoutFailing() {
        val (events, unmapped) =
            WineAndroidImeCommit.mapCommittedCodePoints("a中b") { codePoint, _ ->
                if (codePoint == '中'.code) null else listOf(codePoint)
            }
        assertEquals(listOf('a'.code, 'b'.code), events)
        assertEquals(listOf('中'.code), unmapped)
    }

    @Test
    fun emptyCommitYieldsNothing() {
        val (events, unmapped) =
            WineAndroidImeCommit.mapCommittedCodePoints("") { _, _ -> listOf(1) }
        assertTrue(events.isEmpty())
        assertTrue(unmapped.isEmpty())
    }

    @Test
    fun surrogatePairIsOneCodePoint() {
        val grin = "\uD83D\uDE00" // U+1F600
        val seen = mutableListOf<Int>()
        val (events, unmapped) =
            WineAndroidImeCommit.mapCommittedCodePoints<Int>(grin) { codePoint, chars ->
                seen += codePoint
                assertEquals(2, chars.size)
                null
            }
        assertEquals(listOf(0x1F600), seen)
        assertTrue(events.isEmpty())
        assertEquals(listOf(0x1F600), unmapped)
    }

    @Test
    fun unmappedListIsUnicodeInjectionContract() {
        // Desktop iterates unmappedCodePoints → nativeSendUnicodeChar (native-side).
        val (events, unmapped) =
            WineAndroidImeCommit.mapCommittedCodePoints("x中😀") { codePoint, _ ->
                if (codePoint == 'x'.code) listOf(codePoint) else null
            }
        assertEquals(listOf('x'.code), events)
        assertEquals(listOf('中'.code, 0x1F600), unmapped)
    }
}
