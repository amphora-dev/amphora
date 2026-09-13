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
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.ImageFsInstaller
import com.winlator.cmod.runtime.system.ProcessHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Prepares rootfs / container / prefix / drivers for a wineandroid session
 * **without** constructing Java [com.winlator.cmod.runtime.display.xserver.XServer]
 * or [com.winlator.cmod.runtime.display.environment.components.XServerComponent].
 *
 * Guest exec is [WineAndroidLauncher] (same `explorer /desktop=shell,WxH` shape as X11,
 * no Java XServer). WCP already ships `wineandroid.drv`; unix ioctl client connect
 * to the host socket is still TODO (drv still JNI until a sibling change lands).
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
    data class Prepared(
        val spec: LaunchSpec,
        val container: Container,
        val envVars: Map<String, String>,
        /** Well-known path the future unix bridge will connect to. */
        val bridgeSocketPath: File,
    )

    suspend fun prepare(spec: LaunchSpec): Prepared = withContext(dispatchers.default) {
        require(spec.displayBackend == DisplayBackend.WINEANDROID) {
            "WineAndroidSessionBootstrap requires DisplayBackend.WINEANDROID, got ${spec.displayBackend}"
        }
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
            val env = preparer.envVars() + spec.env
            val socketDir = File(context.filesDir, BRIDGE_DIR).apply { mkdirs() }
            val socketPath = File(socketDir, BRIDGE_SOCK)
            Log.i(
                TAG,
                "wineandroid prefix ready container=${container.id.value} " +
                    "exe=${spec.exePath} envKeys=${env.keys.sorted()} socket=${socketPath.absolutePath}",
            )
            Log.i(
                TAG,
                "prefix ready for WineAndroidLauncher; unix ioctl bridge still TODO " +
                    "until WCP ships wineandroid.drv",
            )
            Prepared(
                spec = spec,
                container = container,
                envVars = env,
                bridgeSocketPath = socketPath,
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

    companion object {
        private const val TAG = "WineAndroidBootstrap"
        const val BRIDGE_DIR = "wineandroid"
        const val BRIDGE_SOCK = "host.sock"
    }
}
