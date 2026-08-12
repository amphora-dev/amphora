package app.amphora.gamesession.input

import java.text.BreakIterator
import java.util.Locale

/** Counts user-visible character boundaries for IME deletion forwarding. */
object GraphemeCounter {
    @JvmStatic
    fun count(text: CharSequence?): Int {
        if (text.isNullOrEmpty()) return 0

        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text.toString())
        var count = 0
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            if (boundary > 0) count++
            boundary = iterator.next()
        }
        return count
    }
}
