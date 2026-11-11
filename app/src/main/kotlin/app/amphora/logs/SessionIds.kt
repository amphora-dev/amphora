package app.amphora.logs

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * A session id, read back: `20261109-143210-notepad` was started at
 * 2026-11-09 14:32:10 and named after "notepad". Ids that do not parse
 * (renamed files) are shown as they are.
 */
data class SessionName(val id: String, val name: String, val startedAt: LocalDateTime?) {
    companion object {
        private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun parse(id: String): SessionName {
            if (id.length < 15) return SessionName(id, id, null)
            val time =
                try {
                    LocalDateTime.parse(id.substring(0, 15), stamp)
                } catch (_: DateTimeParseException) {
                    return SessionName(id, id, null)
                }
            val name = id.substring(15).removePrefix("-").ifEmpty { "session" }
            return SessionName(id, name, time)
        }
    }
}

/** How a log line is classified, for filters and color. */
enum class LineKind {
    Error,
    Warning,
    Amphora,
    Fixme,
    Other,
    ;

    companion object {
        fun of(line: String): LineKind = when {
            line.startsWith("amphora:") -> Amphora
            line.contains(":err:") || line.startsWith("E ") -> Error
            line.contains(":warn:") || line.startsWith("W ") -> Warning
            line.contains(":fixme:") -> Fixme
            else -> Other
        }
    }
}
