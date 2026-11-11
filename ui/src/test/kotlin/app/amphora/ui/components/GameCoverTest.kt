package app.amphora.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCoverTest {
    @Test
    fun `monograms take the first letters of the first two words`() {
        assertEquals("HK", monogram("Hollow Knight"))
        assertEquals("C", monogram("celeste"))
        assertEquals("HL", monogram("half-life_2"))
        assertEquals("DS", monogram("  dark   souls III "))
    }

    @Test
    fun `cjk titles use their first character`() {
        assertEquals("原", monogram("原神"))
        assertEquals("仙", monogram("仙剑奇侠传 98 柔情版"))
    }

    @Test
    fun `an empty title still gets a mark`() {
        assertEquals("?", monogram(""))
        assertEquals("?", monogram(" -_. "))
    }

    @Test
    fun `cover art is stable per seed and varies across seeds`() {
        assertEquals(CoverArt.from("game-1"), CoverArt.from("game-1"))
        val arts = (1..64).map { CoverArt.from("game-$it") }
        assertTrue(arts.map { it.from }.toSet().size > 4)
        assertTrue(arts.map { it.pattern }.toSet().size == 4)
    }
}
