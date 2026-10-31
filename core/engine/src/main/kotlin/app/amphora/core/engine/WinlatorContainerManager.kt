package app.amphora.core.engine

import android.content.Context
import android.util.Log
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
 *    (per-instance state -- see `docs/02-TRACKING.md` §P2 #7c).
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
    private val graphicsDriverCapabilities: GraphicsDriverCapabilities,
    private val sessionPreparer: WineSessionPreparer,
    private val dispatchers: DispatcherProvider,
) : ContainerManager {
    // --- kernel singletons (constructed like the session preparer) -------
    private val contentsManager: ContentsManager = ContentsManager(context)
    private val wnContainerManager: WnContainerManager = WnContainerManager(context)
    private val runtimePinSynchronizer = ContainerRuntimePinSynchronizer()

    override suspend fun getOrCreate(id: ContainerId): AmphoraContainer = withContext(dispatchers.io) {
        val manifest = catalog.require()
        // Wine (Proton) + Box64 + VKD3D + *all* pinned DXVK builds must be
        // installed before launch: the in-session guest probe may relink the
        // prefix to either DXVK flavor, and a mid-session relink can never
        // hit the network.
        contentSource.resolve(ContentComponent.WINE.id)
        contentSource.resolve(ContentComponent.BOX64.id)
        contentSource.resolve(ContentComponent.VKD3D.id)
        contentSource.resolve(ContentComponent.DXVK.id)
        contentSource.resolve(ContentComponent.DXVK_SAREK.id)
        contentSource.resolve(ContentComponent.DXVK_D3D89.id)
        contentsManager.syncContents()

        val wineVersion = resolveWineVersion(manifest)
        val box64Version = resolveBox64Version(manifest)
        val wincomponents = WindowsComponentPreferences.serialized(context)
        val targetId = parseContainerId(id)

        wnContainerManager.loadContainers()
        val existing = wnContainerManager.getContainerById(targetId)
        // Provisional dxwrapper — the last probe verdict if one is cached,
        // else the default build. On a probe cache miss the in-session
        // handshake relinks to the decided flavor before the game starts.
        val dxwrapper = resolveDxwrapper(manifest, dxvkComponent(), d3d89Component())
        val wnContainer =
            existing ?: createDefaultContainer(wineVersion, dxwrapper, wincomponents)
                ?: throw IllegalStateException(
                    "ContainerManager.createContainer returned null for wineVersion=$wineVersion " +
                        "(see logcat 'ContainerManager'); is the Proton prefixPack installed?",
                )
        // Optional adrenotools driver (wrapper default, Turnip when selected).
        ensureAdrenotoolsDriver(wnContainer)
        // Manifest pin (+ installed disk) is authoritative. Container fields are
        // only a cache — rewrite them whenever reconcile moved the install.
        runtimePinSynchronizer.syncRuntimePins(
            container = wnContainer,
            wineVersion = wineVersion,
            wineSha256 = manifest.entry(ContentComponent.WINE)?.sha256.orEmpty(),
            box64Version = box64Version,
            dxwrapper = dxwrapper,
            wincomponents = wincomponents,
            newlyCreated = existing == null,
        )
        check(wnContainerManager.activateContainer(wnContainer)) {
            "Could not activate Wine container ${wnContainer.id}; guest launch aborted"
        }
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
     * The dxwrapper token the in-session probe handshake should relink to for
     * a fresh [report]: the same flavor math the provisional dxwrapper used,
     * but on the new report instead of the cached verdict. All DXVK content
     * profiles were installed by [getOrCreate], so the relink is file ops
     * only — never a download.
     */
    suspend fun dxwrapperForProbeVerdict(report: GuestVulkanProbeReport?): String = withContext(dispatchers.io) {
        resolveDxwrapper(
            catalog.require(),
            dxvkComponentForReport(report),
            d3d89ComponentForReport(report),
        )
    }

    /**
     * Apply a probe verdict mid-session: compute the decided dxwrapper and
     * relink the prefix's DXVK DLLs (shared [WineSessionPreparer.reapplyDxwrapper]
     * path — wipe, applyContent, d3d8/9 split links, applied-mark + container
     * field). Logs the decision.
     */
    suspend fun applyProbeVerdict(container: AmphoraContainer, report: GuestVulkanProbeReport?) {
        val dxwrapper = dxwrapperForProbeVerdict(report)
        Log.i(TAG, "guest probe verdict -> dxwrapper='$dxwrapper' (container ${container.id.value})")
        sessionPreparer.reapplyDxwrapper(container, dxwrapper)
    }

    /**
     * The container's `wineVersion` = the ContentsManager entry name of the
     * installed Proton profile (`type-verName-verCode`). Prefer the manifest's
     * pinned WINE version (single source of truth); fall back to any installed
     * Proton profile, then WinNative's bundled `MAIN_WINE_VERSION` (last resort
     * -- no prefixPack, createContainer's extract step will fail gracefully).
     */
    private fun resolveWineVersion(manifest: ContentManifest): String {
        val preferred = manifest.entry(ContentComponent.WINE)?.version
        val profile =
            ContentPinResolver.resolveInstalledProfile(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_PROTON,
                preferred,
            )
        if (profile != null) return ContentPinResolver.entryName(profile)
        return WineInfo.MAIN_WINE_VERSION.identifier()
    }

    /**
     * Container `box64Version` stores the identity after the type dash
     * (`0.4.5-0db8df775-0`), matching GuestProgramLauncher / preparer lookups.
     */
    private fun resolveBox64Version(manifest: ContentManifest): String {
        val preferred = manifest.entry(ContentComponent.BOX64)?.version
        val profile =
            ContentPinResolver.resolveInstalledProfile(
                contentsManager,
                ContentProfile.ContentType.CONTENT_TYPE_BOX64,
                preferred,
            ) ?: return ""
        return ContentPinResolver.versionIdentity(ContentPinResolver.entryName(profile))
    }

    /**
     * Build the MVP default container config (mirrors `PreparerGraphicsDriverTest`
     * §P2 #7c): wrapper graphics driver + bundled DXVK/VKD3D `.wcp` + full
     * wincomponents. The WinNative `createContainer` auto-selects box64 as the
     * emulator (x86_64 arch) and auto-fills the box64 version from the installed
     * profile.
     *
     * [dxwrapper] must be the ContentsManager-resolvable delimited token
     * (`dxvk-<verName>-<verCode>;vkd3d-<verName>-<verCode>;<ddraw-wrapper>`) so
     * `extractDXWrapperFiles` finds the installed profiles via
     * [ContentsManager.getProfileByEntryName] and `applyContent`s real d3d*
     * DLLs. Do **not** use `dxvk-1.0` / `vkd3d-None` — those never match a
     * profile and fall through to Wine builtins / stubs.
     */
    private fun createDefaultContainer(wineVersion: String, dxwrapper: String, wincomponents: String): WnContainer? {
        val data =
            JSONObject().apply {
                put("name", "Amphora")
                put("wineVersion", wineVersion)
                put("graphicsDriver", WnContainer.DEFAULT_GRAPHICS_DRIVER) // "wrapper"
                // Delimited form: "<dxvkEntry>;<vkd3dEntry>;<ddrawrapper>" (XSDA L7970).
                put("dxwrapper", dxwrapper)
                // graphicsDriverConfig uses ";" delimiter (Container.DEFAULT_GRAPHICSDRIVERCONFIG).
                // version=wrapper selects the loader-facing wrapper ICD for the guest. The
                // wrapper has no Android HAL HMI entry point and wraps system Adreno, so
                // host-side probes resolve this id to System Vulkan. Explicit downloaded
                // Turnip ids remain aligned between host and guest through adrenotools.
                put(
                    "graphicsDriverConfig",
                    "vulkanVersion=1.4;version=wrapper;blacklistedExtensions=;maxDeviceMemory=0;presentMode=mailbox;syncFrame=0;disablePresentWait=0;resourceType=auto;bcnEmulation=auto;bcnEmulationType=compute;bcnEmulationCache=0;gpuName=Device",
                )
                put("wincomponents", wincomponents)
            }
        return wnContainerManager.createContainer(data, contentsManager)
    }

    /**
     * ContentsManager-resolvable DXVK+VKD3D token for the container `dxwrapper`
     * field. Prefers manifest-pinned entries; falls back to any installed
     * profiles of each type. [d3d89Component] adds the optional fourth part —
     * the DXVK install d3d8/d3d9 link into when the launch splits them off.
     */
    private fun resolveDxwrapper(
        manifest: ContentManifest,
        dxvkComponent: ContentComponent,
        d3d89Component: ContentComponent?,
    ): String {
        val dxvk =
            resolveWrapperToken(
                manifest = manifest,
                component = dxvkComponent,
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
        val ddraw =
            DirectDrawWrapperIds.normalize(
                context
                    .getSharedPreferences(DirectDrawWrapperIds.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(DirectDrawWrapperIds.PREFS_KEY_WRAPPER_ID, null),
            )
        val d3d89 =
            d3d89Component?.let {
                resolveWrapperToken(
                    manifest = manifest,
                    component = it,
                    type = ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                    prefix = DxWrapperSelection.D3D89_PREFIX,
                )
            }.orEmpty()
        return DxWrapperSelection(dxvk, vkd3d, ddraw, d3d89).asDelimited()
    }

    /**
     * The driver this device will actually launch with. Reading the stored
     * preference is not enough: a device with no Adreno stack resolves to
     * [GraphicsDriverIds.LEEGAO] from the untouched default.
     */
    private fun effectiveGraphicsDriver(): String =
        graphicsDriverCapabilities.effectiveDriverId(storedGraphicsDriverId())

    private fun dxvkComponent(): ContentComponent {
        val prefs = context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
        val flavor =
            graphicsDriverCapabilities.effectiveDxvkFlavor(
                storedFlavorId = prefs.getString(DxvkFlavorIds.PREFS_KEY_FLAVOR, null),
                storedDriverId = storedGraphicsDriverId(),
            )
        return DxvkFlavorIds.component(flavor)
    }

    /**
     * The component whose pin supplies d3d8/d3d9 when the launch splits them
     * off the main DXVK build, or null when there is no split.
     */
    private fun d3d89Component(): ContentComponent? =
        d3d89ComponentForReport(graphicsDriverCapabilities.guestProbeVerdict(storedGraphicsDriverId())?.report)

    /**
     * Same resolution as [dxvkComponent]/[d3d89Component] but on a fresh
     * probe [report] instead of the cached verdict — used by the in-session
     * handshake right after the probe JSON lands.
     */
    private fun dxvkComponentForReport(report: GuestVulkanProbeReport?): ContentComponent {
        val prefs = context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
        val flavor = DxvkFlavorIds.resolve(prefs.getString(DxvkFlavorIds.PREFS_KEY_FLAVOR, null), report)
        return DxvkFlavorIds.component(flavor)
    }

    private fun d3d89ComponentForReport(report: GuestVulkanProbeReport?): ContentComponent? {
        val prefs = context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
        val flavorId = prefs.getString(DxvkFlavorIds.PREFS_KEY_FLAVOR, null)
        val split =
            DxvkFlavorIds.d3d89Flavor(
                id = flavorId,
                resolvedFlavor = DxvkFlavorIds.resolve(flavorId, report),
                effectiveDriverId = effectiveGraphicsDriver(),
                isAdreno = graphicsDriverCapabilities.isAdrenoDevice(),
            ) ?: return null
        return DxvkFlavorIds.component(split)
    }

    private fun storedGraphicsDriverId(): String? = context
        .getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null)

    private fun resolveWrapperToken(
        manifest: ContentManifest,
        component: ContentComponent,
        type: ContentProfile.ContentType,
        prefix: String,
    ): String {
        val entry = manifest.entry(component)
        val profile =
            ContentPinResolver.resolveInstalledProfile(
                contentsManager,
                type,
                entry?.version,
            )
        if (profile != null) return ContentPinResolver.wrapperToken(prefix, profile)
        val verName = entry?.verName
        val verCode = entry?.verCode ?: 0
        if (verName != null) return "$prefix-$verName-$verCode"
        throw IllegalStateException(
            "No $prefix content profile installed and manifest entry incomplete; " +
                "resolve(${component.name}) before creating a container.",
        )
    }

    /**
     * Apply the user-selected adrenotools id into the container
     * (`graphicsDriverConfig.version`). 设置只写 prefs；这里同步进容器，
     * 之后启动只读容器。
     */
    private suspend fun ensureAdrenotoolsDriver(container: WnContainer) {
        val desired = effectiveGraphicsDriver()
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

    private fun parseContainerId(id: ContainerId): Int = id.value.toIntOrNull() ?: DEFAULT_CONTAINER_ID.value.toInt()

    /** Map a WinNative [WnContainer] to the lean amphora [AmphoraContainer]. */
    private fun WnContainer.toAmphora(): AmphoraContainer = AmphoraContainer(
        id = ContainerId(id.toString()),
        rootPath = rootDir.absolutePath,
        // The Wine prefix lives directly under the container root (home/xuser-<id>/.wine).
        winePrefixPath = File(rootDir, ".wine").absolutePath,
    )

    private companion object {
        const val TAG = "WinlatorContainerMgr"
    }
}
