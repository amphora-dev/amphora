package app.amphora.core.engine

import android.content.Context
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.ContentSource
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.id
import app.amphora.core.container.ContainerManager
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.container.model.ContainerId
import com.winlator.cmod.runtime.container.Container as WnContainer
import com.winlator.cmod.runtime.container.ContainerManager as WnContainerManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.wine.WineInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Real [ContainerManager] (RFC §6 / §7 `runtime/container`). Bridges the lean
 * amphora [ContainerManager] contract to the ported WinNative kernel
 * ([WnContainerManager] / [WnContainer], already in `:core:engine` as part of
 * the P1 `runtime/` port). Same Dependency-Inversion pattern as
 * [ImageFsRootfsInstaller] / [XServerWineSessionPreparer]: the *contract* stays
 * in `:core:container` (low module, no kernel visibility), the *concretion*
 * lives in `:core:engine` next to the `com.winlator.cmod` kernel it adapts.
 *
 * **What this owns (the P4 gap the stub left):**
 * 1. Ensures the bundled Wine (Proton) + Box64 content packages are installed
 *    ([ContentSource.resolve] -- `BundledContentSource`, idempotent via the
 *    `isInstalled` cache). Container creation needs the Proton `prefixPack` +
 *    the Box64 profile (emulator version auto-selection).
 * 2. `syncContents()` on this manager's [ContentsManager] so `createContainer` +
 *    `WineInfo.fromIdentifier` see the installed profiles. The engine and the
 *    preparer each own a separate [ContentsManager] and sync independently
 *    (per-instance state -- see `docs/03-TRACKING.md` §P2 #7c).
 * 3. Create-or-load the WinNative container (id `xuser-<n>` under `home/`),
 *    extracting the Wine prefix from the Proton `prefixPack.txz` on first
 *    creation ([WnContainerManager.createContainer] ->
 *    `extractContainerPatternFile`).
 * 4. `activateContainer`: symlink `home/xuser` -> `home/xuser-<id>` so Wine's
 *    `HOME` (set by `GuestProgramLauncherComponent` to `imageFs.home_path` =
 *    `root/home/xuser`) resolves to this prefix.
 *
 * The amphora [ContainerId] is the WinNative int container id as a string
 * (`"1"`, `"2"`, ...). MVP uses a single shared container (id `"1"`,
 * `ContainerId(DEFAULT_CONTAINER_ID)`); multi-prefix/container management is
 * v0.2 (RFC §9). `getOrCreate` returns the *actual* created/loaded container's
 * id (createContainer auto-assigns `maxContainerId + 1`), so the caller's
 * [LaunchSpec.containerId] is advisory.
 *
 * **Stripped (RFC §7 / D9):** shortcuts (`loadShortcuts`/`upgradeShortcuts` --
 * non-target), duplicate (v0.2 multi-prefix). The WinNative `ContainerManager`
 * shortcut methods are retained in the ported kernel (untouched) but unused.
 */
@Singleton
class WinlatorContainerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentSource: ContentSource,
    private val manifest: ContentManifest,
    private val dispatchers: DispatcherProvider,
) : ContainerManager {

    // --- kernel singletons (constructed like WineEngineImpl / preparer) -------
    private val contentsManager: ContentsManager = ContentsManager(context)
    private val wnContainerManager: WnContainerManager = WnContainerManager(context)

    override suspend fun getOrCreate(id: ContainerId): AmphoraContainer =
        withContext(dispatchers.io) {
            // 1. Bundled Wine (Proton) + Box64 must be installed before a container
            //    can be created (Proton prefixPack / Box64 emulator version). Idempotent.
            contentSource.resolve(ContentComponent.WINE.id)
            contentSource.resolve(ContentComponent.BOX64.id)
            // 2. Load the installed profiles into this manager's ContentsManager.
            contentsManager.syncContents()

            val wineVersion = resolveWineVersion()
            val targetId = parseContainerId(id)

            wnContainerManager.loadContainers()
            val existing = wnContainerManager.getContainerById(targetId)
            val wnContainer = existing ?: createDefaultContainer(wineVersion)
                ?: throw IllegalStateException(
                    "ContainerManager.createContainer returned null for wineVersion=$wineVersion " +
                        "(see logcat 'ContainerManager'); is the Proton prefixPack installed?",
                )
            // 3. Activate: symlink home/xuser -> home/xuser-<id> (Wine HOME target).
            wnContainerManager.activateContainer(wnContainer)
            wnContainer.toAmphora()
        }

    override suspend fun list(): List<AmphoraContainer> = withContext(dispatchers.io) {
        wnContainerManager.loadContainers()
        wnContainerManager.containers.map { it.toAmphora() }
    }

    override suspend fun delete(id: ContainerId): Boolean = withContext(dispatchers.io) {
        val targetId = parseContainerId(id)
        wnContainerManager.loadContainers()
        val container = wnContainerManager.getContainerById(targetId) ?: return@withContext false
        // removeContainer is private in the ported kernel; use the public async API
        // (deletes the container dir + updates the in-memory list, callback on main).
        suspendCancellableCoroutine { cont ->
            wnContainerManager.removeContainerAsync(container) { cont.resume(true) }
        }
    }

    // --- helpers --------------------------------------------------------------

    /**
     * The container's `wineVersion` = the ContentsManager entry name of the
     * installed Proton profile (`type-verName-verCode`). Prefer the manifest's
     * pinned WINE version (single source of truth); fall back to any installed
     * Proton profile, then WinNative's bundled `MAIN_WINE_VERSION` (last resort
     * -- no prefixPack, createContainer's extract step will fail gracefully).
     */
    private fun resolveWineVersion(): String {
        val manifestVersion = manifest.entry(ContentComponent.WINE)?.version
        if (manifestVersion != null) {
            val profile = contentsManager.getProfileByEntryName(manifestVersion)
            if (profile != null && ContentsManager.getInstallDir(context, profile).isDirectory) {
                return manifestVersion
            }
        }
        val profiles = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)
        if (!profiles.isNullOrEmpty()) {
            for (p in profiles) if (p.isInstalled) return ContentsManager.getEntryName(p)
        }
        return WineInfo.MAIN_WINE_VERSION.identifier()
    }

    /**
     * Build the MVP default container config (mirrors `PreparerGraphicsDriverTest`
     * §P2 #7c): wrapper graphics driver + dxvk+vkd3d + full wincomponents. The
     * WinNative `createContainer` auto-selects box64 as the emulator (x86_64
     * arch) and auto-fills the box64 version from the installed profile.
     */
    private fun createDefaultContainer(wineVersion: String): WnContainer? {
        val data = JSONObject().apply {
            put("name", "Amphora")
            put("wineVersion", wineVersion)
            put("graphicsDriver", WnContainer.DEFAULT_GRAPHICS_DRIVER) // "wrapper"
            put("dxwrapper", WnContainer.DEFAULT_DXWRAPPER)            // "dxvk+vkd3d"
            put("wincomponents", WnContainer.FALLBACK_WINCOMPONENTS)
        }
        return wnContainerManager.createContainer(data, contentsManager)
    }

    private fun parseContainerId(id: ContainerId): Int = id.value.toIntOrNull() ?: DEFAULT_CONTAINER_ID

    /** Map a WinNative [WnContainer] to the lean amphora [AmphoraContainer]. */
    private fun WnContainer.toAmphora(): AmphoraContainer = AmphoraContainer(
        id = ContainerId(id.toString()),
        rootPath = rootDir.absolutePath,
        // The Wine prefix lives directly under the container root (home/xuser-<id>/.wine).
        winePrefixPath = File(rootDir, ".wine").absolutePath,
    )

    private companion object {
        /** MVP single shared container (RFC §9: multi-prefix is v0.2). */
        const val DEFAULT_CONTAINER_ID = 1
    }
}
