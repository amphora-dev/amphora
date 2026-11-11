package app.amphora.ui.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Human-readable numbers for readouts. Binary units (a runtime is 845 MiB on
 * disk, not 885 MB) with three significant digits, the way a terminal tool
 * would print them. Pure functions; locale-independent so screenshots and
 * logs read the same everywhere.
 */
public object Formats {
    private val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")

    public fun bytes(value: Long): String {
        if (value < 0) return "-" + bytes(-value)
        if (value < 1024) return "$value B"
        var v = value.toDouble()
        var unit = 0
        while (v >= 1024 && unit < units.lastIndex) {
            v /= 1024
            unit++
        }
        return "${significant(v)} ${units[unit]}"
    }

    public fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    /** 0:42, 2:55, 1:04:09. */
    public fun clock(seconds: Long): String {
        val s = abs(seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val sign = if (seconds < 0) "-" else ""
        return if (h > 0) {
            String.format(Locale.ROOT, "%s%d:%02d:%02d", sign, h, m, sec)
        } else {
            String.format(Locale.ROOT, "%s%d:%02d", sign, m, sec)
        }
    }

    /** Coarse remaining time: "约 3 分钟", "不到 1 分钟". Null when unknown. */
    public fun eta(remainingBytes: Long, bytesPerSecond: Long): String? {
        if (bytesPerSecond <= 0 || remainingBytes < 0) return null
        val seconds = remainingBytes / bytesPerSecond
        return when {
            seconds < 60 -> "不到 1 分钟"
            seconds < 3600 -> "约 ${(seconds / 60.0).roundToLong().coerceAtLeast(1)} 分钟"
            else -> "约 ${String.format(Locale.ROOT, "%.1f", seconds / 3600.0)} 小时"
        }
    }

    /** "37%" from a fraction, floored so 99.6% never shows as done. */
    public fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    /** Relative time for "last played" and log lists. */
    public fun ago(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val seconds = (now - epochMillis) / 1000
        return when {
            seconds < 60 -> "刚刚"
            seconds < 3600 -> "${seconds / 60} 分钟前"
            seconds < 86_400 -> "${seconds / 3600} 小时前"
            seconds < 30 * 86_400 -> "${seconds / 86_400} 天前"
            else -> "${seconds / (30 * 86_400)} 个月前"
        }
    }

    /** A short hash for display: first 12 hex digits. */
    public fun shortHash(hex: String): String = hex.take(12)

    private fun significant(v: Double): String = when {
        v >= 100 -> String.format(Locale.ROOT, "%.0f", v)
        v >= 10 -> String.format(Locale.ROOT, "%.1f", v)
        else -> String.format(Locale.ROOT, "%.2f", v)
    }
}
