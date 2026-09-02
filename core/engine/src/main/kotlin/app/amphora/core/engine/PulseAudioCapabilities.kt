package app.amphora.core.engine

import android.content.Context
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.components.PulseAudioRuntimeSupport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a launch that asked for PulseAudio will actually get it.
 *
 * Page size is a process-constant. [winepulse.so] is a file on the installed
 * Proton/Wine tree: present after the first content apply, missing on a tree
 * that never shipped the driver, unknown when nothing is installed yet.
 */
@Singleton
class PulseAudioCapabilities @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun probe(): PulseAudioProbe = PulseAudioProbe(
        platformSupported = PulseAudioRuntimeSupport.isSupportedPlatform(),
        wineDriver = PulseAudioProbe.wineDriver(wineInstallParents()),
    )

    private fun wineInstallParents(): List<File> {
        val contents = File(context.filesDir, "contents")
        return listOf(
            File(ImageFs.find(context).rootDir, "opt"),
            File(contents, "Proton"),
            File(contents, "Wine"),
        )
    }
}

data class PulseAudioProbe(val platformSupported: Boolean, val wineDriver: WineDriver) {
    enum class WineDriver { PRESENT, MISSING, UNKNOWN }

    /** True when a Pulse request is known to become ALSA. Unknown winepulse still tries Pulse. */
    fun fallsBackToAlsa(): Boolean = !platformSupported || wineDriver == WineDriver.MISSING

    fun fallbackReason(): String? = when {
        !platformSupported ->
            "this device uses 16 KB memory pages, and the bundled AAudio module is built for 4 KB pages"
        wineDriver == WineDriver.MISSING ->
            "the installed Proton build does not include winepulse.so"
        else -> null
    }

    companion object {
        const val WINEPULSE_RELATIVE = "lib/wine/x86_64-unix/winepulse.so"

        fun wineDriver(installParents: List<File>): WineDriver {
            var sawWineTree = false
            for (parent in installParents) {
                val installs = parent.listFiles() ?: continue
                for (install in installs) {
                    if (!install.isDirectory) continue
                    if (File(install, "lib/wine").isDirectory) sawWineTree = true
                    if (File(install, WINEPULSE_RELATIVE).isFile) return WineDriver.PRESENT
                }
            }
            return if (sawWineTree) WineDriver.MISSING else WineDriver.UNKNOWN
        }
    }
}
