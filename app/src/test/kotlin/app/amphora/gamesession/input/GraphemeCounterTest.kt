package app.amphora.gamesession.input

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemeCounterTest {
    @Test
    fun countsChineseCharacters() {
        assertEquals(4, GraphemeCounter.count("中文输入"))
    }

    @Test
    fun keepsSurrogateAndCombiningSequencesTogether() {
        assertEquals(1, GraphemeCounter.count("\uD83D\uDE00"))
        assertEquals(1, GraphemeCounter.count("e\u0301"))
    }

    @Test
    fun handlesEmptyInput() {
        assertEquals(0, GraphemeCounter.count(""))
        assertEquals(0, GraphemeCounter.count(null))
    }
}
