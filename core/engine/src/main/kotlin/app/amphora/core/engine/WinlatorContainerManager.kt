package app.amphora.core.engine

import android.content.Context
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.ContainerManager
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.container.model.ContainerId
import app.amphora.core.container.model.DEFAULT_CONTAINER_ID
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.ContentSource
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.id
import com.winlator.cmod.runtime.container.Container as WnContainer
import com.winlator.cmod.runtime.container.ContainerManager as WnContainerManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.wine.WineInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
 * 1. Ensures the Wine (Proton) + Box64 + DXVK + VKD3D content packages are
 *    installed ([ContentSource.resolve] -- `RemoteContentSource`, idempotent
 *    via the `isInstalled` cache). Container
 *    creation needs the Proton `prefixPack` + the Box64 profile (emulator
 *    version auto-selection); DXVK / VKD3D must be present so
 *    `extractDXWrapperFiles` can `applyContent` real d3d8/9/11/dxgi and d3d12
 *    DLLs (not Wine-builtin stubs).
 * 2. `syncContents()` on this manager's [ContentsManager] so `createContainer` +
 *    `WineInfo.fromIdentifier` see the installed profiles. The engine and the
 *    preparer each own a separate [ContentsManager] and sync independently
 *    (per-instance state -- see `docs/03-TRACKING.md` §P2 #7c).
 * 3. Create-or-load the WinNative container (id `xuser-<n>` under `home/`),
 *    extracting the Wine prefix from the Proton `prefixPack.txz` on first
 *    creation ([WnContainerManager.createContainer] ->
 *    `extractContainerPatternFile`). Existing containers whose `dxwrapper`
 *    still points at a missing DXVK profile or `vkd3d-None` are rewritten to
 *    the bundled DXVK+VKD3D entry so launch picks up real DLLs without wiping
 *    app data.
 * 4. `activateContainer`: symlink `home/xuser` -> `home/xuser-<id>` so Wine's
 *    `HOME` (set by `GuestProgramLauncherComponent` to `imageFs.home_path` =
 *    `root/home/xuser`) resolves to this prefix.
 *
 * The amphora [ContainerId] is the WinNative int container id as a string
 * (`"1"`, `"2"`, ...). MVP uses a single shared container (id `"1"`,
 * [app.amphora.core.container.model.DEFAULT_CONTAINER_ID]); multi-prefix/container management is
 * v0.2 (RFC §9). `getOrCreate` returns the *actual* created/loaded container's
 * id (createContainer auto-assigns `maxContainerId + 1`), so the caller's
 * [LaunchSpec.containerId] is advisory.
 *
 * **Stripped (RFC §7 / D9):** shortcuts (`loadShortcuts`/`upgradeShortcuts` --
 * non-target), duplicate (v0.2 multi-prefix). The WinNative `ContainerManager`
 * shortcut methods are retained in the ported kernel (untouched) but unused.
 */
@Singleton
class WinlatorContainerManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val contentSource: ContentSource,
    private val catalog: ContentCatalog,
    private val turnipProvisioner: TurnipDriverProvisioner,
    private val dispatchers: DispatcherProvider,
) : ContainerManager {
    // --- kernel singletons (constructed like WineEngineImpl / preparer) -------
    private val contentsManager: ContentsManager = ContentsManager(context)
    private val wnContainerManager: WnContainerManager = WnContainerManager(context)

    override suspend fun getOrCreate(id: ContainerId): AmphoraContainer = withContext(dispatchers.io) {
        val manifest = catalog.require()
        // 1. Bundled Wine (Proton) + Box64 + DXVK + VKD3D must be installed
        //    before a container can be created / launched. Idempotent.
        contentSource.resolve(ContentComponent.WINE.id)
        contentSource.resolve(ContentComponent.BOX64.id)
        contentSource.resolve(ContentComponent.DXVK.id)
        contentSource.resolve(ContentComponent.VKD3D.id)
        // 2. Load the installed profiles into this manager's ContentsManager.
        contentsManager.syncContents()

        val wineVersion = resolveWineVersion(manifest)
        val dxwrapper = resolveDxwrapper(manifest)
        val targetId = parseContainerId(id)

        wnContainerManager.loadContainers()
        val existing = wnContainerManager.getContainerById(targetId)
        val wnContainer =
            existing ?: createDefaultContainer(wineVersion, dxwrapper)
                ?: throw IllegalStateException(
                    "ContainerManager.createContainer returned null for wineVersion=$wineVersion " +
                        "(see logcat 'ContainerManager'); is the Proton prefixPack installed?",
                )
        // Follow the manifest WINE pin when it moves under an existing container
        // (resolveWineVersion only runs at creation).
        ensurePinnedWineVersion(wnContainer, wineVersion)
        // Migrate containers created before real DXVK/VKD3D were bundled
        // (dxvk-1.0 / vkd3d-None / missing profile). Clear the dxwrapper gate
        // extra so the preparer re-extracts DLLs on the next launch.
        ensureRealDxwrapper(wnContainer, dxwrapper)
        // One-shot: incomplete upstream DXVK profile.json omitted d3d8/d3d10*;
        // clear the preparer gate so applyContent re-runs with trust augment.
        ensureDxvkTrustAugmentReapply(wnContainer)
        // Optional adrenotools driver (wrapper default, Turnip when selected).
        ensureAdrenotoolsDriver(wnContainer)
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
    private fun resolveWineVersion(manifest: ContentManifest): String {
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
     * §P2 #7c): wrapper graphics driver + bundled DXVK/VKD3D `.wcp` + full
     * wincomponents. The WinNative `createContainer` auto-selects box64 as the
     * emulator (x86_64 arch) and auto-fills the box64 version from the installed
     * profile.
     *
     * [dxwrapper] must be the ContentsManager-resolvable delimited token
     * (`dxvk-<verName>-<verCode>;vkd3d-<verName>-<verCode>;none`) so
     * `extractDXWrapperFiles` finds the installed profiles via
     * [ContentsManager.getProfileByEntryName] and `applyContent`s real d3d*
     * DLLs. Do **not** use `dxvk-1.0` / `vkd3d-None` — those never match a
     * profile and fall through to Wine builtins / stubs.
     */
    private fun createDefaultContainer(wineVersion: String, dxwrapper: String): WnContainer? {
        val data =
            JSONObject().apply {
                put("name", "Amphora")
                put("wineVersion", wineVersion)
                put("graphicsDriver", WnContainer.DEFAULT_GRAPHICS_DRIVER) // "wrapper"
                // Delimited form: "<dxvkEntry>;<vkd3dEntry>;<ddrawrapper>" (XSDA L7970).
                put("dxwrapper", dxwrapper)
                // graphicsDriverConfig uses ";" delimiter (Container.DEFAULT_GRAPHICSDRIVERCONFIG).
                // version=wrapper is the adrenotools driver id — the preparer extracts the bundled
                // Turnip driver from graphics_driver/wrapper.tzst to filesDir/contents/adrenotools/wrapper/
                // so the host VulkanRenderer (which calls adrenotools_open_libvulkan with this id)
                // loads the same Turnip driver as the guest (VK_ICD_FILENAMES=wrapper_icd.aarch64.json).
                // Without this, the host falls back to system Adreno and the guest uses Turnip —
                // two disconnected Vulkan instances = black screen.
                put(
                    "graphicsDriverConfig",
                    "vulkanVersion=1.3;version=wrapper;blacklistedExtensions=;maxDeviceMemory=0;presentMode=mailbox;syncFrame=0;disablePresentWait=1;resourceType=auto;bcnEmulation=auto;bcnEmulationType=compute;bcnEmulationCache=0;gpuName=Device",
                )
                put("wincomponents", WnContainer.FALLBACK_WINCOMPONENTS)
            }
        return wnContainerManager.createContainer(data, contentsManager)
    }

    /**
     * ContentsManager-resolvable DXVK+VKD3D token for the container `dxwrapper`
     * field. Prefers manifest-pinned entries; falls back to any installed
     * profiles of each type.
     */
    private fun resolveDxwrapper(manifest: ContentManifest): String {
        val dxvk =
            resolveWrapperToken(
                manifest = manifest,
                component = ContentComponent.DXVK,
                type = ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                prefix = "dxvk",
            )
        val vkd3d =
            resolveWrapperToken(
                manifest = manifest,
                component = ContentComponent.VKD3D,
                type = ContentProfile.ContentType.CONTENT_TYPE_VKD3D,
                prefix = "vkd3d",
            )
        return "$dxvk;$vkd3d;none"
    }

    private fun resolveWrapperToken(
        manifest: ContentManifest,
        component: ContentComponent,
        type: ContentProfile.ContentType,
        prefix: String,
    ): String {
        val entry = manifest.entry(component)
        val manifestEntryName = entry?.version // e.g. DXVK-3.0.2-gplasync-0
        if (manifestEntryName != null) {
            val profile = contentsManager.getProfileByEntryName(manifestEntryName)
            if (profile != null && ContentsManager.getInstallDir(context, profile).isDirectory) {
                return wrapperToken(prefix, profile)
            }
        }
        val profiles = contentsManager.getProfiles(type)
        if (!profiles.isNullOrEmpty()) {
            for (p in profiles) {
                if (p.isInstalled) return wrapperToken(prefix, p)
            }
        }
        val verName = entry?.verName
        val verCode = entry?.verCode ?: 0
        if (verName != null) return "$prefix-$verName-$verCode"
        throw IllegalStateException(
            "No $prefix content profile installed and manifest entry incomplete; " +
                "resolve(${component.name}) before creating a container.",
        )
    }

    /**
     * Rewrite [container]'s `wineVersion` when the manifest WINE pin moves.
     *
     * [resolveWineVersion] only feeds [createDefaultContainer], so a container
     * created against an older Proton kept pointing at it forever — the new
     * Proton installed but nothing ever ran it. The prefix belongs to the Proton
     * it was unpacked from, so this also arms `wineprefixNeedsUpdate`, which
     * makes the preparer re-extract it from the new `prefixPack.txz`
     * (`repairContainerWinePrefix` carries in-prefix save data across), and
     * clears the dxwrapper gate so DXVK/VKD3D DLLs land in the fresh prefix.
     */
    private fun ensurePinnedWineVersion(container: WnContainer, desired: String) {
        val current = container.getWineVersion() ?: ""
        if (current == desired) return

        android.util.Log.i(
            "WinlatorContainerManager",
            "Migrating container wineVersion '$current' -> '$desired'",
        )
        container.setWineVersion(desired)
        container.putExtra("wineprefixNeedsUpdate", "t")
        container.putExtra("dxwrapper", "")
        container.saveData()
    }

    /**
     * Rewrite [container]'s `dxwrapper` when it differs from the manifest-pinned
     * [desired] token (legacy `dxvk-1.0` / `vkd3d-None` / version bumps). Clears
     * the preparer gate so DLLs are re-applied on next launch.
     */
    private fun ensureRealDxwrapper(container: WnContainer, desired: String) {
        val current = container.getDXWrapper() ?: ""
        if (current == desired) return

        // Always converge on the pinned desired form. Amphora has no UI to keep
        // a custom dxwrapper; preserving an "installed but stale" token blocked
        // DXVK pin migrations (e.g. rolling back a bad 2.4.1 trial).
        android.util.Log.i(
            "WinlatorContainerManager",
            "Migrating container dxwrapper '$current' -> '$desired'",
        )
        container.setDXWrapper(desired)
        container.putExtra("dxwrapper", "")
        container.saveData()
    }

    /**
     * Force one DXVK re-apply after [ContentsManager] started augmenting
     * trust-listed DLLs missing from incomplete upstream `profile.json`
     * (notably `d3d8.dll` / `d3d10.dll` / `d3d10_1.dll` on Dxvk-2.7.1-gplasync).
     */
    private fun ensureDxvkTrustAugmentReapply(container: WnContainer) {
        if (container.getExtra(DXVK_TRUST_AUGMENT_EXTRA) == "1") return
        android.util.Log.i(
            "WinlatorContainerManager",
            "Clearing dxwrapper gate for DXVK trust-file augment re-apply",
        )
        container.putExtra("dxwrapper", "")
        container.putExtra(DXVK_TRUST_AUGMENT_EXTRA, "1")
        container.saveData()
    }

    /**
     * Apply the user-selected adrenotools id (`graphicsDriverConfig.version`).
     * Default [GraphicsDriverIds.WRAPPER]; optional [GraphicsDriverIds.TURNIP_BALANCED]
     * downloads+installs the WN-Turnip zip first.
     */
    private suspend fun ensureAdrenotoolsDriver(container: WnContainer) {
        val prefs = context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
        val desired = GraphicsDriverIds.normalize(prefs.getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null))
        if (desired == GraphicsDriverIds.TURNIP_BALANCED) {
            turnipProvisioner.ensureInstalled()
        }
        val config =
            com.winlator.cmod.runtime.wine.GraphicsDriverConfigUtils
                .parseGraphicsDriverConfig(container.getGraphicsDriverConfig())
        val current = config["version"] ?: GraphicsDriverIds.WRAPPER
        if (current == desired) return
        android.util.Log.i(
            "WinlatorContainerManager",
            "Migrating graphicsDriverConfig.version '$current' -> '$desired'",
        )
        config["version"] = desired
        container.setGraphicsDriverConfig(
            com.winlator.cmod.runtime.wine.GraphicsDriverConfigUtils
                .toGraphicsDriverConfig(config),
        )
        container.saveData()
    }

    /** `<prefix>-<verName>-<verCode>` — lowercase type prefix for preparer matching. */
    private fun wrapperToken(prefix: String, profile: ContentProfile): String =
        "$prefix-${profile.verName}-${profile.verCode}"

    private fun parseContainerId(id: ContainerId): Int = id.value.toIntOrNull() ?: DEFAULT_CONTAINER_ID.value.toInt()

    /** Map a WinNative [WnContainer] to the lean amphora [AmphoraContainer]. */
    private fun WnContainer.toAmphora(): AmphoraContainer = AmphoraContainer(
        id = ContainerId(id.toString()),
        rootPath = rootDir.absolutePath,
        // The Wine prefix lives directly under the container root (home/xuser-<id>/.wine).
        winePrefixPath = File(rootDir, ".wine").absolutePath,
    )

    private companion object {
        /** Marks that the DXVK trust-file augment re-apply has been scheduled once. */
        private const val DXVK_TRUST_AUGMENT_EXTRA = "dxvkTrustAugment"
    }
}
