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
 * 1. Ensures the bundled Wine (Proton) + Box64 + DXVK content packages are
 *    installed ([ContentSource.resolve] -- `BundledContentSource`, idempotent
 *    via the `isInstalled` cache). Container creation needs the Proton
 *    `prefixPack` + the Box64 profile (emulator version auto-selection); DXVK
 *    must be present so `extractDXWrapperFiles` can `applyContent` real
 *    d3d8/9/11/dxgi DLLs (not the d8vk-only ARCHIVE + Wine-builtin fallback).
 * 2. `syncContents()` on this manager's [ContentsManager] so `createContainer` +
 *    `WineInfo.fromIdentifier` see the installed profiles. The engine and the
 *    preparer each own a separate [ContentsManager] and sync independently
 *    (per-instance state -- see `docs/03-TRACKING.md` §P2 #7c).
 * 3. Create-or-load the WinNative container (id `xuser-<n>` under `home/`),
 *    extracting the Wine prefix from the Proton `prefixPack.txz` on first
 *    creation ([WnContainerManager.createContainer] ->
 *    `extractContainerPatternFile`). Existing containers whose `dxwrapper`
 *    still points at the old fake `dxvk-1.0` token are rewritten to the
 *    bundled DXVK entry so launch picks up real DLLs without wiping app data.
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
            // 1. Bundled Wine (Proton) + Box64 + DXVK must be installed before a
            //    container can be created / launched. Idempotent.
            contentSource.resolve(ContentComponent.WINE.id)
            contentSource.resolve(ContentComponent.BOX64.id)
            contentSource.resolve(ContentComponent.DXVK.id)
            // 2. Load the installed profiles into this manager's ContentsManager.
            contentsManager.syncContents()

            val wineVersion = resolveWineVersion()
            val dxwrapper = resolveDxwrapper()
            val targetId = parseContainerId(id)

            wnContainerManager.loadContainers()
            val existing = wnContainerManager.getContainerById(targetId)
            val wnContainer = existing ?: createDefaultContainer(wineVersion, dxwrapper)
                ?: throw IllegalStateException(
                    "ContainerManager.createContainer returned null for wineVersion=$wineVersion " +
                        "(see logcat 'ContainerManager'); is the Proton prefixPack installed?",
                )
            // Migrate containers created before real DXVK was bundled (dxvk-1.0 /
            // missing profile). Clear the dxwrapper gate extra so the preparer
            // re-extracts DLLs on the next launch.
            ensureRealDxvkWrapper(wnContainer, dxwrapper)
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
     * §P2 #7c): wrapper graphics driver + bundled DXVK `.wcp` + full wincomponents.
     * The WinNative `createContainer` auto-selects box64 as the emulator (x86_64
     * arch) and auto-fills the box64 version from the installed profile.
     *
     * [dxwrapper] must be the ContentsManager-resolvable token
     * (`dxvk-<verName>-<verCode>`) so `extractDXWrapperFiles` finds the installed
     * profile via [ContentsManager.getProfileByEntryName] and `applyContent`s real
     * d3d8/9/11/dxgi DLLs. Do **not** use `dxvk-1.0` — that never matched a
     * profile and previously fell through to d8vk-only + Wine builtins.
     */
    private fun createDefaultContainer(wineVersion: String, dxwrapper: String): WnContainer? {
        val data = JSONObject().apply {
            put("name", "Amphora")
            put("wineVersion", wineVersion)
            put("graphicsDriver", WnContainer.DEFAULT_GRAPHICS_DRIVER) // "wrapper"
            // Delimited form: "<dxvkEntry>;<vkd3dEntry>;<ddrawrapper>" (XSDA L7970).
            // vkd3d-None / none = no D3D12 / ddraw replacement for MVP.
            put("dxwrapper", dxwrapper)
            // graphicsDriverConfig uses ";" delimiter (Container.DEFAULT_GRAPHICSDRIVERCONFIG).
            // version=wrapper is the adrenotools driver id — the preparer extracts the bundled
            // Turnip driver from graphics_driver/wrapper.tzst to filesDir/contents/adrenotools/wrapper/
            // so the host VulkanRenderer (which calls adrenotools_open_libvulkan with this id)
            // loads the same Turnip driver as the guest (VK_ICD_FILENAMES=wrapper_icd.aarch64.json).
            // Without this, the host falls back to system Adreno and the guest uses Turnip —
            // two disconnected Vulkan instances = black screen.
            put("graphicsDriverConfig", "vulkanVersion=1.3;version=wrapper;blacklistedExtensions=;maxDeviceMemory=0;presentMode=mailbox;syncFrame=0;disablePresentWait=1;resourceType=auto;bcnEmulation=auto;bcnEmulationType=compute;bcnEmulationCache=0;gpuName=Device")
            put("wincomponents", WnContainer.FALLBACK_WINCOMPONENTS)
        }
        return wnContainerManager.createContainer(data, contentsManager)
    }

    /**
     * ContentsManager-resolvable DXVK token for the container `dxwrapper` field.
     * Prefers the manifest-pinned DXVK entry (`DXVK-<verName>-<verCode>` →
     * `dxvk-<verName>-<verCode>` so [String.contains] `"dxvk"` still matches in
     * the preparer); falls back to any installed DXVK profile.
     */
    private fun resolveDxwrapper(): String {
        val entry = manifest.entry(ContentComponent.DXVK)
        val manifestEntryName = entry?.version // e.g. DXVK-3.0.2-gplasync-0
        if (manifestEntryName != null) {
            val profile = contentsManager.getProfileByEntryName(manifestEntryName)
            if (profile != null && ContentsManager.getInstallDir(context, profile).isDirectory) {
                return dxvkWrapperToken(profile)
            }
        }
        val profiles = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)
        if (!profiles.isNullOrEmpty()) {
            for (p in profiles) {
                if (p.isInstalled) return dxvkWrapperToken(p)
            }
        }
        // Last resort: derive from manifest fields even if syncContents hasn't
        // indexed the profile yet (create path still installs via ContentSource).
        val verName = entry?.verName
        val verCode = entry?.verCode ?: 0
        if (verName != null) return "dxvk-$verName-$verCode;vkd3d-None;none"
        throw IllegalStateException(
            "No DXVK content profile installed and manifest entry incomplete; " +
                "run ./gradlew :app:stageBundledContent and resolve(DXVK).",
        )
    }

    /**
     * Rewrite [container]'s `dxwrapper` when it doesn't resolve to an installed
     * DXVK profile (legacy `dxvk-1.0` / empty / mistyped). Clears the preparer
     * gate extra so DLLs are re-applied on next launch.
     */
    private fun ensureRealDxvkWrapper(container: WnContainer, desired: String) {
        val current = container.getDXWrapper() ?: ""
        val currentDxvk = current.split(";").firstOrNull().orEmpty()
        val resolved = contentsManager.getProfileByEntryName(currentDxvk)
        if (resolved != null && ContentsManager.getInstallDir(context, resolved).isDirectory) {
            return
        }
        if (current == desired) return
        android.util.Log.i(
            "WinlatorContainerManager",
            "Migrating container dxwrapper '$current' -> '$desired' (no installed DXVK profile matched)",
        )
        container.setDXWrapper(desired)
        // Force preparer extractDXWrapperFiles gate on next launch.
        container.putExtra("dxwrapper", "")
        container.saveData()
    }

    /** `dxvk-<verName>-<verCode>` — lowercase type prefix for preparer `contains("dxvk")`. */
    private fun dxvkWrapperToken(profile: ContentProfile): String =
        "dxvk-${profile.verName}-${profile.verCode};vkd3d-None;none"

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
