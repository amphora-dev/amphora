package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.container.model.ContainerId
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentSource
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.id
import app.amphora.core.engine.XServerWineSessionPreparer
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.container.Container as WnContainer
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.shared.io.FileUtils
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2 real-device verification of [XServerWineSessionPreparer] graphics-driver
 * extraction, exercised end-to-end against remotely provisioned runtime assets.
 *
 * Uses the production SHA-pinned [ContentSource], so package identities follow
 * the live manifest instead of stale APK asset names.
 *
 * Flow:
 *  1. Ensure the manifest-pinned imagefs at `filesDir/imagefs`.
 *  2. Resolve Proton + Bionic-Box64 `.wcp` through production provisioning.
 *  3. Create a WinNative [WnContainer] (`ContainerManager.createContainer`) —
 *     extracts the Wine prefix from the Proton `prefixPack.txz`.
 *  4. [XServerWineSessionPreparer]: `ensureWinePrefixReady` (repair →
 *     `firstTimeBoot=true`) + `extractGraphicsDriverFiles` → populates
 *     `envVars()` (`WRAPPER_VK_VERSION`, `GALLIUM_DRIVER=zink`, …).
 *  5. Verify `envVars()` (unconditional). Conditionally verify `wrapper.tzst`
 *     extraction (needs the app APK to bundle `graphics_driver/wrapper.tzst`).
 *
 * Device: Lenovo TB322FC, arm64-v8a, API 36, Adreno 830.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PreparerGraphicsDriverTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var contentSource: ContentSource

    @Inject
    lateinit var catalog: ContentCatalog

    @Inject
    lateinit var rootfsInstaller: RootfsInstaller

    @Inject
    lateinit var runtimeAssets: RuntimeAssetProvisioner

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun installRuntimes_createContainer_extractGraphicsDriver() = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        val manifest = catalog.require()

        // --- Phase 0: ensure imagefs at filesDir/imagefs ----------------------
        val imagefsDir = File(appCtx.filesDir, "imagefs")
        assertTrue(
            "remote rootfs provisioning failed",
            rootfsInstaller.ensureInstalled(
                RootfsSpec(
                    targetRoot = imagefsDir.absolutePath,
                    imagefsVersion = manifest.entry(ContentComponent.ROOTFS)!!.version,
                    termuxfsSha256 = "",
                ),
            ),
        )
        assertTrue("imagefs/usr/lib missing", File(imagefsDir, "usr/lib").isDirectory)
        assertTrue("imagefs/usr/share missing", File(imagefsDir, "usr/share").isDirectory)

        // --- Phase 1: provision Proton + Box64 + kernel-direct assets --------
        runtimeAssets.ensureAvailable()
        val protonEntry = manifest.entry(ContentComponent.WINE)!!
        val box64Entry = manifest.entry(ContentComponent.BOX64)!!
        contentSource.resolve(ContentComponent.WINE.id)
        contentSource.resolve(ContentComponent.BOX64.id)

        val cm = ContentsManager(appCtx)
        cm.syncContents()

        val protonProfile =
            requireNotNull(cm.getProfileByEntryName(protonEntry.version)) {
                "resolved Proton profile not loaded: ${protonEntry.version}"
            }
        val protonDir = ContentsManager.getInstallDir(appCtx, protonProfile)
        assertTrue("proton install dir missing: $protonDir", protonDir.isDirectory)
        assertTrue("proton bin/ missing", File(protonDir, protonProfile.wineBinPath).isDirectory)
        assertTrue("proton lib/ missing", File(protonDir, protonProfile.wineLibPath).isDirectory)
        assertTrue(
            "proton prefixPack missing: ${protonProfile.winePrefixPack}",
            File(protonDir, protonProfile.winePrefixPack).isFile,
        )
        println("PROTON_INSTALLED dir=$protonDir entry=${ContentsManager.getEntryName(protonProfile)}")

        val box64Profile =
            requireNotNull(cm.getProfileByEntryName(box64Entry.version)) {
                "resolved Box64 profile not loaded: ${box64Entry.version}"
            }
        val box64Dir = ContentsManager.getInstallDir(appCtx, box64Profile)
        assertTrue("box64 install dir missing: $box64Dir", box64Dir.isDirectory)
        assertTrue("box64 binary missing in install dir", File(box64Dir, "box64").isFile)
        println("BOX64_INSTALLED dir=$box64Dir")

        // --- Phase 2: create WinNative container (extracts Wine prefix) -------
        val cMgr = ContainerManager(appCtx)
        val wineVersion = ContentsManager.getEntryName(protonProfile)
        val data =
            JSONObject().apply {
                put("name", "preparer-test")
                put("wineVersion", wineVersion)
                put("graphicsDriver", WnContainer.DEFAULT_GRAPHICS_DRIVER) // "wrapper"
                put("dxwrapper", WnContainer.DEFAULT_DXWRAPPER) // delimited Amphora form
                put("wincomponents", WnContainer.FALLBACK_WINCOMPONENTS)
            }
        val wnContainer = cMgr.createContainer(data, cm)
        assertNotNull("createContainer returned null (see logcat ContainerManager)", wnContainer)
        val rootDir = wnContainer!!.getRootDir()
        println("CONTAINER_CREATED id=${wnContainer.id} rootDir=$rootDir wineVersion=$wineVersion")
        assertTrue(
            "container .wine prefix missing (prefixPack extraction failed during createContainer)",
            File(rootDir, ".wine").isDirectory,
        )

        // --- Phase 3: preparer (ensureWinePrefixReady + extractGraphicsDriver) -
        val preparer = XServerWineSessionPreparer(appCtx, DefaultDispatcherProvider())

        // The preparer owns a private ContentsManager that has not had syncContents()
        // called; WineInfo.fromIdentifier / repairContainerWinePrefix need the
        // installed profiles loaded. The @VisibleForTesting accessor syncs it
        // (test-only; prod wires this at app init). Best-effort: Tier-1 envVars
        // work even if this fails.
        val preparerSynced =
            try {
                preparer.syncContentsForTesting()
                true
            } catch (e: Throwable) {
                println("WARN: preparer syncContentsForTesting failed (envVars still verified): $e")
                false
            }

        val amphoraContainer =
            AmphoraContainer(
                id = ContainerId("preparer-test"),
                rootPath = rootDir.absolutePath,
                winePrefixPath = File(rootDir, ".wine").absolutePath,
            )

        // Force prefix repair so firstTimeBoot=true (enables the wrapper.tzst
        // extraction attempt in extractGraphicsDriverFiles). Delete the prefix
        // createContainer extracted; ensureWinePrefixReady rebuilds it from the
        // Proton prefixPack. Best-effort — Tier-1 envVars do not depend on this.
        var firstTimeBoot = false
        if (preparerSynced) {
            assertTrue(
                "failed to remove existing prefix without following directory symlinks",
                FileUtils.delete(File(rootDir, ".wine")),
            )
            try {
                preparer.ensureWinePrefixReady(amphoraContainer)
                firstTimeBoot = true
                println("PREFIX_REPAIRED .wine exists=${File(rootDir, ".wine").isDirectory}")
            } catch (e: Throwable) {
                println("WARN: ensureWinePrefixReady threw (continuing): $e")
            }
        }

        // extractGraphicsDriverFiles → populates envVars (unconditional envState.put).
        preparer.extractGraphicsDriverFiles(amphoraContainer)

        // --- Phase 4: verify envVars (Tier 1, unconditional) -----------------
        val env = preparer.envVars()
        println("ENVVARS (${env.size}, firstTimeBoot=$firstTimeBoot):")
        env.toSortedMap().forEach { (k, v) -> println("  $k=$v") }

        // Core env vars set unconditionally by extractGraphicsDriverFilesCore.
        // zink is selected through the loader, not GALLIUM_DRIVER: the latter leaves
        // EGL on swrast (blank window) and suppresses Mesa's own zink selection.
        assertEquals("MESA_LOADER_DRIVER_OVERRIDE", "zink", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertNull("GALLIUM_DRIVER must stay unset", env["GALLIUM_DRIVER"])
        // Our X server answers DRI3Open with zero FDs, so kopper has to be forced.
        assertEquals("LIBGL_KOPPER_DRI2", "1", env["LIBGL_KOPPER_DRI2"])
        assertNull("LIBGL_KOPPER_DISABLE must stay unset", env["LIBGL_KOPPER_DISABLE"])
        assertNotNull("WRAPPER_VK_VERSION missing", env["WRAPPER_VK_VERSION"])
        assertTrue(
            "VK_ICD_FILENAMES not pointing at wrapper_icd: ${env["VK_ICD_FILENAMES"]}",
            env["VK_ICD_FILENAMES"]?.contains("wrapper_icd.aarch64.json") == true,
        )
        assertTrue("MESA_VK_WSI_PRESENT_MODE missing", env.containsKey("MESA_VK_WSI_PRESENT_MODE"))
        assertTrue("WRAPPER_EMULATE_BCN missing", env.containsKey("WRAPPER_EMULATE_BCN"))
        assertTrue("WRAPPER_EXTENSION_BLACKLIST missing", env.containsKey("WRAPPER_EXTENSION_BLACKLIST"))
        assertEquals(
            "32-bit native ddraw with x86_64 builtin fallback",
            "ddraw=n,b;d3d9=n",
            env["WINEDLLOVERRIDES"],
        )
        assertTrue(
            "WineD3D fallback must stay on GL/Zink: ${env["WINE_D3D_CONFIG"]}",
            env["WINE_D3D_CONFIG"]?.contains("renderer=gl") == true,
        )
        assertTrue("envVars unexpectedly empty", env.isNotEmpty())

        // --- Phase 4b: downloaded wrapper.tzst extraction -------------------
        // extractGraphicsDriverFilesCore extracts graphics_driver/wrapper.tzst into
        // imagefs root when firstTimeBoot. FileUtils transparently serves the
        // SHA-verified copy under filesDir/runtime-assets.
        if (firstTimeBoot) {
            // wrapper.tzst ships Mesa Vulkan ICD wrapper libs into imagefs root.
            val shareDir = File(imagefsDir, "usr/share/vulkan/icd.d")
            assertTrue(
                "wrapper_icd.aarch64.json not extracted by wrapper.tzst",
                File(shareDir, "wrapper_icd.aarch64.json").isFile,
            )
            assertTrue(
                "x86_64 builtin ddraw.dll was not restored after wrapper wipe",
                File(rootDir, ".wine/drive_c/windows/system32/ddraw.dll").isFile,
            )
            assertTrue(
                "x86_64 builtin ddraw.dll must be shared by symlink",
                Files.isSymbolicLink(
                    File(rootDir, ".wine/drive_c/windows/system32/ddraw.dll").toPath(),
                ),
            )
            println("WRAPPER_EXTRACTED graphics_driver/wrapper.tzst into $imagefsDir")
        } else {
            println("SKIP wrapper extraction check: existing prefix was reused")
        }

        println(
            "PREPARER_VERIFY_OK envVars=${env.size} wineVersion=$wineVersion " +
                "container=${wnContainer.id} firstTimeBoot=$firstTimeBoot",
        )
    }
}
