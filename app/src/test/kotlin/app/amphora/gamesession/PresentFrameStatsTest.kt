package app.amphora.gamesession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresentFrameStatsTest {
    private val ms = 1_000_000L

    private fun steady(count: Int, intervalMs: Long, endNs: Long): LongArray =
        LongArray(count) { endNs - (count - 1 - it) * intervalMs * ms }

    @Test
    fun steadySixtyFps() {
        val now = 10_000 * ms
        val stats = PresentFrameStats.compute(steady(100, 16, now), now)!!

        assertEquals(62.5f, stats.fps, 0.01f)
        assertEquals(16f, stats.frameTimeP95Ms, 0.001f)
        assertEquals(62.5f, stats.onePercentLowFps, 0.01f)
    }

    @Test
    fun onlyTheLastTwoSecondsCount() {
        val now = 10_000 * ms
        // 30 fps long ago, then 1.5 s of 100 fps.
        val old = steady(50, 33, now - 3_000 * ms)
        val recent = steady(151, 10, now)
        val stats = PresentFrameStats.compute(old + recent, now)!!

        assertEquals(100f, stats.fps, 0.01f)
    }

    @Test
    fun hitchesShowInP95AndOnePercentLow() {
        val now = 10_000 * ms
        // 198 frames at 5 ms with two 50 ms hitches.
        val times = mutableListOf(now - 1_000 * ms)
        repeat(199) { i -> times += times.last() + (if (i == 50 || i == 150) 50 else 5) * ms }
        val shifted = times.map { it - (times.last() - now) }.toLongArray()
        val stats = PresentFrameStats.compute(shifted, now)!!

        assertEquals(5f, stats.frameTimeP95Ms, 0.001f)
        assertEquals(20f, stats.onePercentLowFps, 0.01f)
    }

    @Test
    fun stalledOrEmptyWindowHasNoStats() {
        val now = 10_000 * ms
        assertNull(PresentFrameStats.compute(LongArray(0), now))
        assertNull(PresentFrameStats.compute(longArrayOf(now), now))
        assertNull(PresentFrameStats.compute(steady(60, 16, now - 1_500 * ms), now))
    }
}
