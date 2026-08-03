package app.amphora.core.engine

import android.content.Context
import java.io.File

/**
 * App-private locations the guest reads, owned by Amphora rather than the ported
 * kernel (which owns `imagefs/` via `ImageFs` and `contents/` via `ContentsManager`).
 */
object GuestFiles {
    /**
     * Where a picked or bundled `.exe` is staged before launch. App-private but
     * guest-readable, and it has to be the same directory for both the launcher's
     * SAF copy and the debug smoke-test fixture — [app.amphora.core.engine.model.LaunchSpec.exePath]
     * points into it.
     */
    fun exeDir(context: Context): File = File(context.filesDir, "exe")
}
