package app.amphora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.rootfs.RootfsInstaller
import app.amphora.core.rootfs.model.RootfsSpec
import com.winlator.cmod.runtime.display.environment.ImageFsInstaller
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import javax.inject.Inject

/**
 * End-to-end verification of remote rootfs provisioning on real hardware.
 *
 * A cold run downloads, verifies and atomically installs the self-built
 * `amphora-dev/imagefs` Release (`imagefs.txz`); a warm run validates the
 * installed-version fast path without requiring APK assets.
 *
 * Device: Lenovo TB322FC, arm64-v8a, API 36, Adreno 830.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ImagefsExtractionTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var rootfsInstaller: RootfsInstaller

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun provisionRealImagefsFromRemoteSource() = runBlocking {
        val appCtx = ApplicationProvider.getApplicationContext<Context>()
        val outDir = File(appCtx.filesDir, "imagefs")

        val t0 = System.currentTimeMillis()
        val ok = rootfsInstaller.ensureInstalled(
            RootfsSpec(
                targetRoot = outDir.absolutePath,
                imagefsVersion = ImageFsInstaller.LATEST_VERSION.toString(),
                termuxfsSha256 = "",
            ),
        )
        val dtMs = System.currentTimeMillis() - t0
        assertTrue("remote rootfs provisioning failed (dt=${dtMs}ms)", ok)
        assertEquals(
            ImageFsInstaller.LATEST_VERSION.toString(),
            rootfsInstaller.currentVersion(),
        )

        // --- verify extracted Bionic rootfs structure ---
        assertTrue("usr/lib missing", File(outDir, "usr/lib").isDirectory)
        assertTrue("usr/bin missing", File(outDir, "usr/bin").isDirectory)
        assertTrue("usr/lib/libc.so missing", File(outDir, "usr/lib/libc.so").exists())
        assertTrue("usr/lib/libvulkan.so missing", File(outDir, "usr/lib/libvulkan.so").exists())
        assertTrue(
            "usr/lib/libasound.so missing",
            File(outDir, "usr/lib/libasound.so").exists() ||
                File(outDir, "usr/lib/libasound.so.2").exists(),
        )
        assertTrue(
            "android_aserver ALSA plugin missing",
            File(outDir, "usr/lib/asound_module_pcm_android_aserver.so").exists(),
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

        // Do not recursively walk an active imagefs: Wine creates directory
        // symlinks that can form cycles. Fixed structural checks above plus a
        // top-level sanity check prove the install without following links.
        val topLevelEntries = outDir.list().orEmpty().size
        assertTrue(
            "installed imagefs has too few top-level entries: $topLevelEntries",
            topLevelEntries >= 8,
        )

        println(
            "IMAGEFS_PROVISION_OK topLevelEntries=$topLevelEntries " +
                "dt_ms=$dtMs out=${outDir.absolutePath}",
        )
    }
}
