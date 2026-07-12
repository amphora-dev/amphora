package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.shared.io.TarCompressorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * P2 end-to-end verification of the rootfs extraction path on real hardware.
 *
 * Exercises the exact code path `ImageFsRootfsInstaller` relies on:
 * `TarCompressorUtils.extract(Type.ZSTD, ctx, "imagefs.tzst", outDir)` ->
 * `NativeContentIO.extractAsset` (native, `libwinlator.so`, zstd). The 190 MB
 * real `imagefs.tzst` asset (SHA `0902e324...`, WinNative Git LFS) is carried in
 * the test APK's assets (git-ignored; see `docs/04-ASSET-MANIFEST.md`).
 *
 * Device: Lenovo TB322FC, arm64-v8a, API 36, Adreno 830.
 */
@RunWith(AndroidJUnit4::class)
class ImagefsExtractionTest {

    @Test
    fun extractRealImagefsAsset() {
        // Test APK context -> its assets carry imagefs.tzst.
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val assetName = "imagefs.tzst"

        val assets = testCtx.assets.list("").orEmpty().toList()
        // The 190MB imagefs.tzst is git-ignored (*.tzst); skip (not fail) when not staged.
        assumeTrue(
            "imagefs.tzst not staged in androidTest/assets (have: $assets); see docs/04-ASSET-MANIFEST.md",
            assetName in assets,
        )

        // Extract into the app's internal filesDir (where ImageFs.find() resolves).
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        val outDir = File(appCtx.filesDir, "imagefs_test_extract")
        outDir.deleteRecursively()
        assertTrue("mkdirs failed", outDir.mkdirs())

        val t0 = System.currentTimeMillis()
        val ok = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, testCtx, assetName, outDir,
        )
        val dtMs = System.currentTimeMillis() - t0
        assertTrue("TarCompressorUtils.extract returned false (dt=${dtMs}ms)", ok)

        // --- verify extracted Bionic rootfs structure ---
        assertTrue("usr/lib missing", File(outDir, "usr/lib").isDirectory)
        assertTrue("usr/bin missing", File(outDir, "usr/bin").isDirectory)
        assertTrue("usr/lib/libc.so missing", File(outDir, "usr/lib/libc.so").exists())
        assertTrue("usr/lib/libvulkan.so missing", File(outDir, "usr/lib/libvulkan.so").exists())
        assertTrue("usr/lib/libpulse.so missing", File(outDir, "usr/lib/libpulse.so").exists())
        assertTrue(
            "usr/lib/libpulseaudio.so missing",
            File(outDir, "usr/lib/libpulseaudio.so").exists(),
        )
        assertTrue(
            "usr/etc/alsa/conf.d/android_aserver.conf missing",
            File(outDir, "usr/etc/alsa/conf.d/android_aserver.conf").exists(),
        )

        // merged-usr symlinks (bin/lib/etc -> usr/*)
        assertTrue("bin symlink missing (merged-usr)", File(outDir, "bin").exists())
        assertTrue("lib symlink missing (merged-usr)", File(outDir, "lib").exists())

        // Bionic proof: libc.so is a symlink to /system/lib64/libc.so (not glibc libc.so.6).
        val libcPath = File(outDir, "usr/lib/libc.so").toPath()
        assertTrue("usr/lib/libc.so is not a symlink", Files.isSymbolicLink(libcPath))
        val libcTarget = Files.readSymbolicLink(libcPath).toString()
        assertEquals("libc.so not Bionic (/system/lib64)", "/system/lib64/libc.so", libcTarget)

        // file-count sanity (imagefs has ~10,892 tar entries)
        val count = outDir.walkTopDown().count()
        assertTrue("extracted entry count too low: $count (expect >5000)", count > 5000)

        println("IMAGEFS_EXTRACT_OK entries=$count dt_ms=$dtMs out=${outDir.absolutePath}")
    }
}
