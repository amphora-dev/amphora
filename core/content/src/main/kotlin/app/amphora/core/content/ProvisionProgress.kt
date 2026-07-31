package app.amphora.core.content

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One step of remote content / rootfs provisioning shown in the launcher and
 * game-session UI.
 */
data class ProvisionProgress(
    /** Coarse stage id (`manifest`, `download`, `rootfs`, `extract`, …). */
    val stage: String,
    /** Human-readable label for the current file or action. */
    val detail: String = "",
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { total ->
            (bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

/** Process-wide bus so downloaders and the UI share one progress stream. */
class ProvisionProgressBus {
    private val _progress = MutableStateFlow<ProvisionProgress?>(null)
    val progress: StateFlow<ProvisionProgress?> = _progress.asStateFlow()

    fun update(progress: ProvisionProgress) {
        _progress.value = progress
    }

    fun clear() {
        _progress.value = null
    }
}
