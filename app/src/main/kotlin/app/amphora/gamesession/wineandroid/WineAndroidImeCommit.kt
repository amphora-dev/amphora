package app.amphora.gamesession.wineandroid

import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Map IME-committed text to Android [KeyEvent]s for wineandroid KEYBOARD_EVENT.
 *
 * ASCII/Latin that [KeyCharacterMap] can encode become DOWN/UP pairs (same pipe as
 * hardware keys). Unmapped code points (typical CJK / emoji) are returned in
 * [MappedCommit.unmappedCodePoints] for the desktop to inject via
 * [WineAndroidNative.nativeSendUnicodeChar] (KEYEVENTF_UNICODE) — not IMM32/TSF.
 */
object WineAndroidImeCommit {
    const val MAX_IME_DELETE_COUNT = 256

    data class MappedCommit(val events: List<KeyEvent>, val unmappedCodePoints: List<Int>)

    /**
     * Pure mapping over committed text: for each Unicode code point, call [eventsForChars]
     * with that code point's UTF-16 [CharArray]. Null/empty → code point listed as unmapped.
     */
    fun <T> mapCommittedCodePoints(
        text: CharSequence,
        eventsForChars: (codePoint: Int, chars: CharArray) -> List<T>?,
    ): Pair<List<T>, List<Int>> {
        if (text.isEmpty()) return emptyList<T>() to emptyList()
        val events = ArrayList<T>(text.length * 2)
        val unmapped = ArrayList<Int>()
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val chars = Character.toChars(codePoint)
            val mapped = eventsForChars(codePoint, chars)
            if (mapped.isNullOrEmpty()) {
                unmapped += codePoint
            } else {
                events.addAll(mapped)
            }
            index += Character.charCount(codePoint)
        }
        return events to unmapped
    }

    fun mapCommittedText(
        text: CharSequence,
        keyCharacterMap: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD),
    ): MappedCommit {
        val (events, unmapped) =
            mapCommittedCodePoints(text) { codePoint, chars ->
                val mapped =
                    try {
                        keyCharacterMap.getEvents(chars)
                    } catch (t: Throwable) {
                        Log.w(TAG, "KeyCharacterMap.getEvents failed for U+${codePoint.toString(16)}", t)
                        null
                    }
                mapped?.toList()
            }
        for (codePoint in unmapped) {
            Log.d(
                TAG,
                "IME commit unmapped codePoint=U+${codePoint.toString(16)} " +
                    "(caller injects KEYEVENTF_UNICODE)",
            )
        }
        return MappedCommit(events = events, unmappedCodePoints = unmapped)
    }

    fun tapKeyEvents(keyCode: Int, metaState: Int = 0): List<KeyEvent> {
        val now = android.os.SystemClock.uptimeMillis()
        return listOf(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState),
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState),
        )
    }

    private const val TAG = "WineAndroidImeCommit"
}
