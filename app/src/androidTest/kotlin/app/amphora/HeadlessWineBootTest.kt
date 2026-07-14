package app.amphora

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.container.model.ContainerId
import app.amphora.core.engine.WineHeadlessRunner
import com.winlator.cmod.runtime.container.Container as WnContainer
import com.winlator.cmod.runtime.container.ContainerManager as WnContainerManager
import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.content.ContentsManager.InstallFailedReason
import com.winlator.cmod.runtime.content.ContentsManager.OnInstallFinishedCallback
import com.winlator.cmod.shared.io.TarCompressorUtils
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * No-GPU test path (RFC §8 minus the Vulkan render): proves the
 * box64 + Wine + Bionic-rootfs + prefix stack runs on a host **without an
 * Adreno GPU** -- e.g. an Android emulator on Apple Silicon -- by executing a
 * headless Wine command ([WineHeadlessRunner]) that never creates a VkInstance.
 *
 * This isolates the CPU stack (rootfs + content + prefix + box64 + wine) from
 * the Adreno-bound Vulkan present, so it can iterate on a mac ARM emulator
 * without the real device. The Adreno device is still required for the full
 * §8 acceptance (visible Vulkan frame + touch + audio).
 *
 * Setup mirrors `PreparerGraphicsDriverTest` (imagefs + Proton/Box64 .wcp install
 * + container creation); the difference is the final step hands the container to
 * [WineHeadlessRunner] instead of the X-server/Vulkan launch path.
 *
 * Host prerequisites:
 * ```
 * adb push Proton-10.0-4-x86_64.wcp        /sdcard/Android/data/app.amphora/files/
 * adb push Bionic-Box64-0.4.3-8ee3d8f2c.wcp /sdcard/Android/data/app.amphora/files/
 * ```
 * plus `imagefs.tzst` in `app/androidTest/assets/` (git-ignored; copy from the
 * WinNative checkout). Tests `assumeTrue`-skip when an asset is absent.
 *
 * Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.amphora.HeadlessWineBootTest`
 */
@RunWith(AndroidJUnit4::class)
class HeadlessWineBootTest {

    /** `box64 wine --version` prints the Wine version and exits 0 (no prefix mutation, no display). */
    @Test
    fun headlessWine_versionPrints() = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        val container = setUpContainer(appCtx)

        val runner = WineHeadlessRunner(appCtx, DefaultDispatcherProvider())
        val result = runner.run(container, "--version")

        println("HEADLESS_WINE_VERSION exit=${result.exitCode} timedOut=${result.timedOut} stdout=${result.stdout.trim()}")
        assertTrue(
            "wine --version did not exit 0 (exit=${result.exitCode} timedOut=${result.timedOut}): ${result.stdout.take(500)}",
            result.exitCode == 0,
        )
        assertTrue(
            "stdout should name wine, got: ${result.stdout}",
            result.stdout.contains("wine", ignoreCase = true),
        )
    }

    /** `box64 wine wineboot --init` initializes/updates the prefix headlessly and exits 0. */
    @Test
    fun headlessWineboot_initSucceeds() = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        val container = setUpContainer(appCtx)

        val runner = WineHeadlessRunner(appCtx, DefaultDispatcherProvider())
        val result = runner.run(container, "wineboot --init", timeoutSec = 180)

        println("HEADLESS_WINEBOOT exit=${result.exitCode} timedOut=${result.timedOut} stdout=${result.stdout.take(500)}")
        assertTrue(
            "wineboot --init did not exit 0 (exit=${result.exitCode} timedOut=${result.timedOut}): ${result.stdout.take(1000)}",
            result.exitCode == 0,
        )
        // wineboot updates the prefix registry.
        assertTrue(
            "user.reg missing after wineboot: ${File(container.winePrefixPath, "user.reg")}",
            File(container.winePrefixPath, "user.reg").isFile,
        )
    }

    // --- shared setup (mirrors PreparerGraphicsDriverTest Phases 0-2) --------

    private suspend fun setUpContainer(appCtx: Context): AmphoraContainer {
        val testCtx = InstrumentationRegistry.getInstrumentation().context

        // Phase 0: imagefs at filesDir/imagefs.
        val imagefsDir = File(appCtx.filesDir, "imagefs")
        ensureImagefs(testCtx, imagefsDir)
        assertTrue("imagefs/usr/lib missing", File(imagefsDir, "usr/lib").isDirectory)

        // Phase 1: install Proton + Box64 .wcp (local, bypasses D4 download stub).
        val extDir = appCtx.getExternalFilesDir(null)
        assumeTrue("external files dir unavailable", extDir != null)
        val protonWcp = File(extDir, "Proton-10.0-4-x86_64.wcp")
        val box64Wcp = File(extDir, "Bionic-Box64-0.4.3-8ee3d8f2c.wcp")
        assumeTrue("Proton .wcp not pushed to $extDir", protonWcp.exists())
        assumeTrue("Box64 .wcp not pushed to $extDir", box64Wcp.exists())

        val cm = ContentsManager(appCtx)
        cm.syncContents()
        var protonProfile = cm.getProfileByEntryName(PROTON_ENTRY)
        if (protonProfile == null || !ContentsManager.getInstallDir(appCtx, protonProfile).isDirectory) {
            protonProfile = installWcp(cm, protonWcp); cm.syncContents()
        }
        var box64Profile = cm.getProfileByEntryName(BOX64_ENTRY)
        if (box64Profile == null || !ContentsManager.getInstallDir(appCtx, box64Profile).isDirectory) {
            box64Profile = installWcp(cm, box64Wcp); cm.syncContents()
        }

        // Phase 2: create the container (extracts the Wine prefix from the Proton prefixPack).
        val cMgr = WnContainerManager(appCtx)
        val wineVersion = ContentsManager.getEntryName(protonProfile)
        val data = JSONObject().apply {
            put("name", "headless-test")
            put("wineVersion", wineVersion)
            put("graphicsDriver", WnContainer.DEFAULT_GRAPHICS_DRIVER)
            put("dxwrapper", WnContainer.DEFAULT_DXWRAPPER)
            put("wincomponents", WnContainer.FALLBACK_WINCOMPONENTS)
        }
        val wnContainer = cMgr.createContainer(data, cm)
            ?: error("createContainer returned null (see logcat 'ContainerManager')")
        cMgr.activateContainer(wnContainer)
        assertTrue(
            "container .wine prefix missing (prefixPack extraction failed)",
            File(wnContainer.getRootDir(), ".wine").isDirectory,
        )

        return AmphoraContainer(
            id = ContainerId(wnContainer.id.toString()),
            rootPath = wnContainer.getRootDir().absolutePath,
            winePrefixPath = File(wnContainer.getRootDir(), ".wine").absolutePath,
        )
    }

    /** Extract imagefs.tzst (test asset) to filesDir/imagefs if usr/lib absent. */
    private fun ensureImagefs(testCtx: Context, imagefsDir: File) {
        if (File(imagefsDir, "usr/lib").isDirectory) return
        val assets = testCtx.assets.list("").orEmpty().toList()
        assumeTrue(
            "imagefs.tzst not staged in androidTest/assets (have: $assets); see docs/04-ASSET-MANIFEST.md",
            "imagefs.tzst" in assets,
        )
        imagefsDir.deleteRecursively()
        assertTrue("mkdirs imagefs failed", imagefsDir.mkdirs())
        val ok = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, testCtx, "imagefs.tzst", imagefsDir,
        )
        assertTrue("imagefs extract failed", ok)
    }

    /** Install a .wcp via extraContentFile + finishInstallContent (idempotent). */
    private fun installWcp(cm: ContentsManager, wcp: File): ContentProfile {
        val result = arrayOfNulls<ContentProfile>(1)
        val error = arrayOfNulls<InstallFailedReason>(1)
        val latch = CountDownLatch(1)
        cm.extraContentFile(Uri.fromFile(wcp), object : OnInstallFinishedCallback {
            override fun onSucceed(profile: ContentProfile) {
                cm.finishInstallContent(profile, object : OnInstallFinishedCallback {
                    override fun onSucceed(p: ContentProfile) {
                        result[0] = profile; latch.countDown()
                    }

                    override fun onFailed(reason: InstallFailedReason, e: Exception?) {
                        if (reason == InstallFailedReason.ERROR_EXIST) {
                            result[0] = profile; latch.countDown()
                        } else {
                            error[0] = reason; latch.countDown()
                        }
                    }
                })
            }

            override fun onFailed(reason: InstallFailedReason, e: Exception?) {
                error[0] = reason; latch.countDown()
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
        private const val BOX64_ENTRY = "Box64-0.4.3-8ee3d8f2c-0"
    }
}
