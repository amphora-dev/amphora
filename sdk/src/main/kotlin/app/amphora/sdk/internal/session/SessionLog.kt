package app.amphora.sdk.internal.session

import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.settings.EffectiveConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The SDK's lines in a session log. Wine's output goes to the same file
 * (O_APPEND from both processes), so these are prefixed to stand apart.
 */
internal class SessionLog(private val file: File) {
    fun start() {
        file.parentFile?.mkdirs()
        write("", append = false)
    }

    fun line(message: String) {
        write("$PREFIX$message\n")
    }

    fun header(spec: SessionSpec, config: EffectiveConfig, warnings: List<String>) {
        val text =
            buildString {
                appendLine("${PREFIX}session ${spec.id} · ${spec.title}")
                appendLine("${PREFIX}runtime ${spec.runtimeName}")
                appendLine("${PREFIX}prefix ${spec.prefixDir}")
                appendLine("${PREFIX}cwd ${spec.workingDir}")
                appendLine("${PREFIX}desktop ${spec.desktop} scale=${spec.scale} dpi=${spec.dpi}")
                config.env.forEach { appendLine("${PREFIX}env ${it.name}=${it.value}  [${it.origin}]") }
                config.rejectedEnv.forEach { appendLine("${PREFIX}env refused ${it.name} (engine-owned)") }
                config.inactive.forEach { appendLine("${PREFIX}inactive ${it.spec.id}=${it.value}") }
                warnings.forEach { appendLine("${PREFIX}warning $it") }
                appendLine("$ ${spec.argv().joinToString(" ") { quote(it) }}")
            }
        write(text)
    }

    private fun write(text: String, append: Boolean = true) {
        try {
            FileOutputStream(file, append).use { it.write(text.encodeToByteArray()) }
        } catch (e: IOException) {
            AmphoraLog.w("cannot write $file", e)
        }
    }

    companion object {
        const val PREFIX = "amphora: "

        /** Shell-style quoting, for display only. */
        fun quote(arg: String): String =
            if (arg.isNotEmpty() && arg.none { it.isWhitespace() || it == '"' || it == '\'' }) {
                arg
            } else {
                "'" + arg.replace("'", "'\\''") + "'"
            }
    }
}
