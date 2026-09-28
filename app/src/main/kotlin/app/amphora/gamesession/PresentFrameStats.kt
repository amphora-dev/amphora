package app.amphora.gamesession

import kotlin.math.ceil

/** Frame pacing over a window of present timestamps. */
internal data class PresentFrameStats(val fps: Float, val frameTimeP95Ms: Float, val onePercentLowFps: Float) {
    companion object {
        /** Span the HUD averages over. */
        const val WINDOW_NS = 2_000_000_000L

        /** A window whose last present is older than this is stalled, not slow. */
        private const val STALL_NS = 1_000_000_000L

        /**
         * Stats for [timesNs] (ascending present times, same clock as [nowNs]) inside
         * the [WINDOW_NS] before [nowNs]. Null when fewer than two presents fall in
         * the window or the last one is more than [STALL_NS] old.
         *
         * FPS counts intervals over the span they cover, so it does not dip at the
         * window edges. The 1% low is the mean of the slowest 1% of frame times.
         */
        fun compute(timesNs: LongArray, nowNs: Long): PresentFrameStats? {
            val inWindow = timesNs.filter { it in (nowNs - WINDOW_NS)..nowNs }
            if (inWindow.size < 2 || nowNs - inWindow.last() > STALL_NS) return null
            val intervalsMs = inWindow.zipWithNext { a, b -> (b - a) / 1_000_000f }
            val spanMs = (inWindow.last() - inWindow.first()) / 1_000_000f
            if (spanMs <= 0f) return null
            val sorted = intervalsMs.sorted()
            val p95 = sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)]
            val slowest = sorted.takeLast(ceil(sorted.size * 0.01).toInt().coerceAtLeast(1))
            return PresentFrameStats(
                fps = intervalsMs.size * 1_000f / spanMs,
                frameTimeP95Ms = p95,
                onePercentLowFps = 1_000f / slowest.average().toFloat(),
            )
        }
    }
}
