package app.amphora.sdk.internal.logs

import androidx.core.content.FileProvider
import app.amphora.sdk.R

/** Serves session logs to apps the user shares them with; nothing else is reachable. */
internal class LogFileProvider : FileProvider(R.xml.amphora_log_paths) {
    internal companion object {
        fun authority(packageName: String): String = "$packageName.amphora.logs"
    }
}
