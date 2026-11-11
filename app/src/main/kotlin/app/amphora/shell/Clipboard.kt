package app.amphora.shell

import android.content.ClipData
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import app.amphora.LocalAppGraph
import app.amphora.Message
import kotlinx.coroutines.launch

/**
 * Copies text for bug reports. Android 13+ confirms copies itself, so the
 * app only says so on older versions. Very long text is cut from the start:
 * the clipboard goes through a binder transaction with a size limit.
 */
@Composable
fun rememberCopier(): (label: String, text: String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val messages = LocalAppGraph.current.messages
    return remember(clipboard, scope, messages) {
        { label, text ->
            val clipped = if (text.length > MAX_CHARS) "…\n" + text.takeLast(MAX_CHARS) else text
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, clipped)))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) messages.show("已复制$label", Message.Tone.Ok)
            }
        }
    }
}

private const val MAX_CHARS = 100_000

/** POSIX shell quoting, so a copied command line pastes back into a shell intact. */
fun shellQuote(arg: String): String = if (arg.isNotEmpty() &&
    arg.all { it.isLetterOrDigit() || it in "@%+=:,./-_" }
) {
    arg
} else {
    "'" + arg.replace("'", "'\\''") + "'"
}
