package app.amphora.gamesession.wineandroid

import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.ContainerManager
import app.amphora.core.container.model.Container
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ProvisionProgress
import app.amphora.core.content.ProvisionProgressBus
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.engine.WineSessionPreparer
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.ImageFsInstaller
import com.winlator.cmod.runtime.system.ProcessHelper
import com.winlator.cmod.runtime.wine.WineRegistryEditor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Prepares rootfs / container / prefix / drivers for a wineandroid session
 * **without** constructing Java XServer / XServerComponent.
 *
 * Guest exec is [WineAndroidLauncher]. Wine connects to the host via upstream
 * abstract `\0\Device\WineAndroid` (no filesystem host.sock).
 */
@Singleton
class WineAndroidSessionBootstrap
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val containerManager: ContainerManager,
    private val rootfsInstaller: RootfsInstaller,
    private val preparer: WineSessionPreparer,
    private val runtimeAssets: RuntimeAssetProvisioner,
    private val catalog: ContentCatalog,
    private val progressBus: ProvisionProgressBus,
    private val dispatchers: DispatcherProvider,
) {
    data class Prepared(val spec: LaunchSpec, val container: Container, val envVars: Map<String, String>)

    suspend fun prepare(spec: LaunchSpec): Prepared = withContext(dispatchers.default) {
        ProcessHelper.init(context)
        try {
            progressBus.update(ProvisionProgress(stage = "manifest", detail = "Fetching content manifest…"))
            catalog.require()
            progressBus.update(ProvisionProgress(stage = "runtime", detail = "Preparing runtime assets…"))
            runtimeAssets.ensureAvailable()
            progressBus.update(ProvisionProgress(stage = "rootfs", detail = "Checking imagefs…"))
            ensureRootfs()
            progressBus.update(ProvisionProgress(stage = "container", detail = "Preparing Wine container…"))
            val container = containerManager.getOrCreate(spec.containerId)
            progressBus.update(ProvisionProgress(stage = "prefix", detail = "Setting up Wine prefix…"))
            preparer.setupWineSystemFiles(spec, container)
            preparer.extractGraphicsDriverFiles(container)
            pinAndroidGraphicsDriver(container)
            val env = preparer.envVars() + spec.env
            Log.i(
                TAG,
                "wineandroid prefix ready container=${container.id.value} " +
                    "exe=${spec.exePath} envKeys=${env.keys.sorted()} " +
                    "ipc=abstract\\\\0\\\\Device\\\\WineAndroid",
            )
            Log.i(TAG, "prefix ready; HKCU Software\\Wine\\Drivers Graphics=android")
            Prepared(
                spec = spec,
                container = container,
                envVars = env,
            )
        } finally {
            progressBus.clear()
        }
    }

    private suspend fun ensureRootfs() {
        val imageFs = ImageFs.find(context)
        val installed =
            rootfsInstaller.ensureInstalled(
                RootfsSpec(
                    targetRoot = imageFs.getRootDir().absolutePath,
                    imagefsVersion = ImageFsInstaller.LATEST_VERSION.toString(),
                    termuxfsSha256 = "",
                ),
            )
        check(installed) {
            "Rootfs installation failed while preparing wineandroid session"
        }
    }

    /**
     * Explorer default_driver is mac,x11,wayland; android is not on that list.
     * Without this, winefile loads winex11.drv and dies looking for DISPLAY.
     */
    private fun pinAndroidGraphicsDriver(container: Container) {
        val userReg = File(container.winePrefixPath, "user.reg")
        if (!userReg.isFile) {
            Log.w(TAG, "missing $userReg, cannot pin Graphics=android")
            return
        }
        WineRegistryEditor(userReg).use { editor ->
            val current = editor.getStringValue("Software\\Wine\\Drivers", "Graphics")
            if (current != "android") {
                editor.setStringValue("Software\\Wine\\Drivers", "Graphics", "android")
                Log.i(TAG, "Wine graphics driver set to android (was $current)")
            }
        }
    }

    companion object {
        private const val TAG = "WineAndroidBootstrap"
    }
}
