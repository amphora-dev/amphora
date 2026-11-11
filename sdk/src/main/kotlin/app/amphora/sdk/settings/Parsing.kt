package app.amphora.sdk.settings

/** Width and height of a Wine desktop. */
public data class Resolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x$height"

    public companion object {
        public fun parse(text: String): Resolution? {
            val match = Regex("""(\d{3,4})\s*[xX×]\s*(\d{3,4})""").matchEntire(text.trim()) ?: return null
            val r = Resolution(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            return r.takeIf {
                it.width in 640..3840 && it.height in 360..2160 && it.width % 8 == 0 && it.height % 2 == 0
            }
        }

        /**
         * The desktop for a resolution setting. "screen" keeps the 720p height
         * and takes the width from the screen's aspect ratio, rounded down to a
         * multiple of 8.
         */
        public fun resolve(choice: String, custom: String, screenWidth: Int, screenHeight: Int): Resolution {
            if (choice == "screen" && screenWidth > 0 && screenHeight > 0) {
                val long = maxOf(screenWidth, screenHeight).toDouble()
                val short = minOf(screenWidth, screenHeight).toDouble()
                val width = (720 * long / short / 8).toInt() * 8
                return Resolution(width.coerceIn(960, 1920), 720)
            }
            if (choice == "custom") parse(custom)?.let { return it }
            return parse(choice) ?: Resolution(1280, 720)
        }
    }
}

internal fun checkResolution(value: String): String? =
    if (value.isEmpty() || Resolution.parse(value) != null) null else "格式如 1280x800；宽 640–3840 且为 8 的倍数，高 360–2160"

private val dllName = Regex("""[A-Za-z0-9_.\-*]+""")
private val dllModes = setOf("", "n", "b", "n,b", "b,n")

/** "a,b=n,b;c=" → [(a, n,b), (b, n,b), (c, "")]. Null when malformed. */
internal fun parseDllOverridesOrNull(text: String): List<Pair<String, String>>? {
    val out = ArrayList<Pair<String, String>>()
    for (raw in text.split(';')) {
        val entry = raw.trim()
        if (entry.isEmpty()) continue
        val eq = entry.indexOf('=')
        if (eq <= 0) return null
        val mode = entry.substring(eq + 1).replace(" ", "")
        if (mode !in dllModes) return null
        for (name in entry.substring(0, eq).split(',')) {
            val n = name.trim()
            if (!dllName.matches(n)) return null
            out += n.lowercase().removeSuffix(".dll") to mode
        }
    }
    return out
}

internal fun parseDllOverrides(text: String): List<Pair<String, String>> = parseDllOverridesOrNull(text).orEmpty()

internal fun checkDllOverrides(value: String): String? = if (parseDllOverridesOrNull(value) !=
    null
) {
    null
} else {
    "格式如 d3dx9_43=n,b;xinput1_3=b；模式只能是 n、b、n,b、b,n 或留空"
}

/** Splits a command line: whitespace separates, single or double quotes group. Null on unbalanced quotes. */
public fun splitArgs(text: String): List<String>? {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var inToken = false
    for (c in text) {
        when {
            quote != null && c == quote -> quote = null
            quote != null -> current.append(c)
            c == '"' || c == '\'' -> {
                quote = c
                inToken = true
            }
            c.isWhitespace() -> {
                if (inToken) out += current.toString()
                current.clear()
                inToken = false
            }
            else -> {
                current.append(c)
                inToken = true
            }
        }
    }
    if (quote != null) return null
    if (inToken) out += current.toString()
    return out
}

private val envName = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

/** NAME=value lines; blank lines and # comments ignored. Null when a line is malformed. */
internal fun parseEnvOrNull(text: String): List<Pair<String, String>>? {
    val out = ArrayList<Pair<String, String>>()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val name = line.substring(0, eq).trim()
        if (!envName.matches(name)) return null
        out += name to line.substring(eq + 1)
    }
    return out
}

internal fun parseEnv(text: String): List<Pair<String, String>> = parseEnvOrNull(text).orEmpty()

internal fun checkEnv(value: String): String? = if (parseEnvOrNull(value) !=
    null
) {
    null
} else {
    "每行一个 NAME=value，名字只能有字母、数字和下划线"
}
