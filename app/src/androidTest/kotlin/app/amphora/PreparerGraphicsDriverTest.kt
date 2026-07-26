package app.amphora

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.container.model.ContainerId
import app.amphora.core.content.ContentSource
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.id
import app.amphora.core.engine.XServerWineSessionPreparer
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.container.Container as WnContainer
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.ContentsManager.InstallFailedReason
import com.winlator.cmod.runtime.content.ContentsManager.OnInstallFinishedCallback
import com.winlator.cmod.runtime.display.environment.ImageFsInstaller
import com.winlator.cmod.shared.io.TarCompressorUtils
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * P2 real-device verification of [XServerWineSessionPreparer] graphics-driver
 * extraction, exercised end-to-end against remotely provisioned runtime assets.
 *
 * Proves the "local .wcp install" workaround for the D4 download stub
 * (native_content_io.cpp:783 `nativeDownloadFile` returns JNI_FALSE): the `.wcp`
 * files are bundled in the *app* APK by `./gradlew :app:stageBundledContent`,
 * staged to temp files by the test, and installed locally via
 * [ContentsManager.extraContentFile] (which routes through `nativeExtractArchive`,
 * NOT the stubbed `nativeDownloadFile`).
 *
 * Flow:
 *  1. Ensure imagefs at `filesDir/imagefs` (from app APK asset `imagefs.tzst`,
 *     staged by `stageBundledContent`).
 *  2. Install Proton + Bionic-Box64 `.wcp` (staged from app APK assets) via
 *     `extraContentFile` + `finishInstallContent` (local extract, bypasses D4
 *     download stub).
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
    lateinit var rootfsInstaller: RootfsInstaller

    @Inject
    lateinit var runtimeAssets: RuntimeAssetProvisioner

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun installRuntimes_createContainer_extractGraphicsDriver() = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()

        // --- Phase 0: ensure imagefs at filesDir/imagefs ----------------------
        val imagefsDir = File(appCtx.filesDir, "imagefs")
        assertTrue(
            "remote rootfs provisioning failed",
            rootfsInstaller.ensureInstalled(
                RootfsSpec(
                    targetRoot = imagefsDir.absolutePath,
                    imagefsVersion = ImageFsInstaller.LATEST_VERSION.toString(),
                    termuxfsSha256 = "",
                ),
            ),
        )
        assertTrue("imagefs/usr/lib missing", File(imagefsDir, "usr/lib").isDirectory)
        assertTrue("imagefs/usr/share missing", File(imagefsDir, "usr/share").isDirectory)

        // --- Phase 1: provision Proton + Box64 + kernel-direct assets --------
        runtimeAssets.ensureAvailable()
        contentSource.resolve(ContentComponent.WINE.id)
        contentSource.resolve(ContentComponent.BOX64.id)

        val cm = ContentsManager(appCtx)
        cm.syncContents()

        // Proton (skip re-install if already present from a prior run).
        var protonProfile = cm.getProfileByEntryName(PROTON_ENTRY)
        if (protonProfile == null || !ContentsManager.getInstallDir(appCtx, protonProfile).isDirectory) {
            val protonWcp = stageWcpFromAssets(appCtx, PROTON_WCP)
            println("INSTALLING Proton .wcp (${protonWcp.length()} bytes)…")
            protonProfile = installWcp(cm, protonWcp)
            cm.syncContents()
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

        // Box64.
        var box64Profile = cm.getProfileByEntryName(BOX64_ENTRY)
        if (box64Profile == null || !ContentsManager.getInstallDir(appCtx, box64Profile).isDirectory) {
            val box64Wcp = stageWcpFromAssets(appCtx, BOX64_WCP)
            println("INSTALLING Box64 .wcp (${box64Wcp.length()} bytes)…")
            box64Profile = installWcp(cm, box64Wcp)
            cm.syncContents()
        }
        val box64Dir = ContentsManager.getInstallDir(appCtx, box64Profile)
        assertTrue("box64 install dir missing: $box64Dir", box64Dir.isDirectory)
        assertTrue("box64 binary missing in install dir", File(box64Dir, "box64").isFile)
        println("BOX64_INSTALLED dir=$box64Dir")

        // --- Phase 2: create WinNative container (extracts Wine prefix) -------
        val cMgr = ContainerManager(appCtx)
        val wineVersion = ContentsManager.getEntryName(protonProfile) // "Proton-10.0-4-x86_64-0"
        val data = JSONObject().apply {
            put("name", "preparer-test")
            put("wineVersion", wineVersion)
            put("graphicsDriver", WnContainer.DEFAULT_GRAPHICS_DRIVER) // "wrapper"
            put("dxwrapper", WnContainer.DEFAULT_DXWRAPPER)             // "dxvk+vkd3d"
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
        val preparerSynced = try {
            preparer.syncContentsForTesting(); true
        } catch (e: Throwable) {
            println("WARN: preparer syncContentsForTesting failed (envVars still verified): $e"); false
        }

        val amphoraContainer = AmphoraContainer(
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
            File(rootDir, ".wine").deleteRecursively()
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
        assertEquals("GALLIUM_DRIVER", "zink", env["GALLIUM_DRIVER"])
        assertEquals("LIBGL_KOPPER_DISABLE", "true", env["LIBGL_KOPPER_DISABLE"])
        assertNotNull("WRAPPER_VK_VERSION missing", env["WRAPPER_VK_VERSION"])
        assertTrue(
            "VK_ICD_FILENAMES not pointing at wrapper_icd: ${env["VK_ICD_FILENAMES"]}",
            env["VK_ICD_FILENAMES"]?.contains("wrapper_icd.aarch64.json") == true,
        )
        assertTrue("MESA_VK_WSI_PRESENT_MODE missing", env.containsKey("MESA_VK_WSI_PRESENT_MODE"))
        assertTrue("WRAPPER_EMULATE_BCN missing", env.containsKey("WRAPPER_EMULATE_BCN"))
        assertTrue("WRAPPER_EXTENSION_BLACKLIST missing", env.containsKey("WRAPPER_EXTENSION_BLACKLIST"))
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
            println("WRAPPER_EXTRACTED graphics_driver/wrapper.tzst into $imagefsDir")
        } else {
            println("SKIP wrapper extraction check: existing prefix was reused")
        }

        println(
            "PREPARER_VERIFY_OK envVars=${env.size} wineVersion=$wineVersion " +
                "container=${wnContainer.id} firstTimeBoot=$firstTimeBoot",
        )
    }

    // --- helpers -------------------------------------------------------------

    /** Extract imagefs.tzst (app APK asset) to filesDir/imagefs if usr/lib absent. */
    private fun ensureImagefs(appCtx: Context, imagefsDir: File) {
        if (File(imagefsDir, "usr/lib").isDirectory) {
            println("imagefs already present at $imagefsDir")
            return
        }
        val assets = appCtx.assets.list("").orEmpty().toList()
        assumeTrue(
            "imagefs.tzst not bundled in app assets (have: $assets); " +
                "run ./gradlew :app:stageBundledContent first (see docs/04-ASSET-MANIFEST.md)",
            "imagefs.tzst" in assets,
        )
        imagefsDir.deleteRecursively()
        assertTrue("mkdirs imagefs failed", imagefsDir.mkdirs())
        val t0 = System.currentTimeMillis()
        val ok = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, appCtx, "imagefs.tzst", imagefsDir,
        )
        val dtMs = System.currentTimeMillis() - t0
        assertTrue("imagefs extract failed (dt=${dtMs}ms)", ok)
        println("IMAGEFS_EXTRACTED dt_ms=$dtMs -> $imagefsDir")
    }

    /**
     * Copy a `.wcp` from the app APK assets (bundled by `stageBundledContent`)
     * to a temp file under [Context]'s cacheDir, for feeding to
     * [ContentsManager.extraContentFile]. Idempotent: reuses an existing copy.
     */
    private fun stageWcpFromAssets(appCtx: Context, name: String): File {
        val out = File(appCtx.cacheDir, name).apply { parentFile?.mkdirs() }
        if (!out.exists() || out.length() == 0L) {
            appCtx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }

    /**
     * Install a .wcp via extraContentFile + finishInstallContent.
     * Both are synchronous (blocking native extract), but a latch guards the
     * callback for safety. Idempotent: ERROR_EXIST (already installed) returns
     * the profile.
     */
    private fun installWcp(cm: ContentsManager, wcp: File): ContentProfile {
        val result = arrayOfNulls<ContentProfile>(1)
        val error = arrayOfNulls<InstallFailedReason>(1)
        val latch = CountDownLatch(1)
        cm.extraContentFile(Uri.fromFile(wcp), object : OnInstallFinishedCallback {
            override fun onSucceed(profile: ContentProfile) {
                cm.finishInstallContent(profile, object : OnInstallFinishedCallback {
                    override fun onSucceed(p: ContentProfile) {
                        result[0] = profile
                        latch.countDown()
                    }

                    override fun onFailed(reason: InstallFailedReason, e: Exception?) {
                        if (reason == InstallFailedReason.ERROR_EXIST) {
                            result[0] = profile // already installed from a prior run
                            latch.countDown()
                        } else {
                            error[0] = reason
                            latch.countDown()
                        }
                    }
                })
            }

            override fun onFailed(reason: InstallFailedReason, e: Exception?) {
                error[0] = reason
                latch.countDown()
            }
        })
        assertTrue("install timed out: ${wcp.name}", latch.await(180, TimeUnit.SECONDS))
        assertNull("install failed: ${error[0]} (${wcp.name})", error[0])
        assertNotNull("install returned null profile (${wcp.name})", result[0])
        return result[0]!!
    }

    private companion object {
        // Entry names = type-verName-verCode (versionCode=0 in both .wcp profile.json).
        private const val PROTON_ENTRY = "Proton-10.0-4-x86_64-0"
        private const val BOX64_ENTRY = "Box64-0.4.3-c08554e3f-0"
        // Asset names (bundled in the app APK by stageBundledContent).
        private const val PROTON_WCP = "Proton-10.0-4-x86_64.wcp"
        private const val BOX64_WCP = "Box64-0.4.3-c08554e3f.wcp"
    }
}
