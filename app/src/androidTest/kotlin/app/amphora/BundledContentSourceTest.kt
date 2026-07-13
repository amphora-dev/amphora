package app.amphora

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.content.BundledContentSource
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.model.ContentArtifact
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.id
import app.amphora.core.engine.WinlatorBundledAssetInstaller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real-device verification of [BundledContentSource] -- the production
 * replacement for the `PreparerGraphicsDriverTest` host `curl` + `adb push`
 * workaround. When the `.wcp` / `.tzst` assets are bundled in the *app* APK
 * `assets/`, `resolve()` SHA-verifies and installs them locally with no remote
 * download (bypasses the D4 `nativeDownloadFile` stub via
 * `ContentsManager.extraContentFile`).
 *
 * Three tiers, each `assumeTrue`-gated on the corresponding asset being staged
 * in `app/src/main/assets/` (assets are git-ignored `*.tzst`/`*.wcp`; see
 * `docs/04-ASSET-MANIFEST.md`):
 *  1. Manifest load (always -- verifies the shipped `content_manifest.json`).
 *  2. TURNIP ARCHIVE resolve (needs `graphics_driver/wrapper.tzst`).
 *  3. WINE WCP resolve (needs `Proton-10.0-4-x86_64.wcp`).
 *
 * Device: Lenovo TB322FC, arm64-v8a, API 36, Adreno 830.
 */
@RunWith(AndroidJUnit4::class)
class BundledContentSourceTest {

    private val appCtx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun manifest_loadsAndParsesAllEntries() {
        val manifest = ContentManifest.load(appCtx)
        val ids = manifest.all().map { it.component }.toSet()
        assertTrue("wine entry missing", ContentComponent.WINE in ids)
        assertTrue("box64 entry missing", ContentComponent.BOX64 in ids)
        assertTrue("turnip entry missing", ContentComponent.TURNIP in ids)
        // Each WCP entry must carry enough to compute getInstallDir.
        val wine = manifest.entry(ContentComponent.WINE)!!
        assertNotNull("wine contentType required for getInstallDir", wine.contentType)
        assertNotNull("wine verName required for getInstallDir", wine.verName)
        println("MANIFEST_OK entries=${ids.size} wine=${wine.version} turnip=${manifest.entry(ContentComponent.TURNIP)!!.version}")
    }

    @Test
    fun resolve_turnip_archive_extractsWithShaVerify() = runBlocking {
        val present = appCtx.assets.list("graphics_driver").orEmpty().toList()
        assumeTrue(
            "graphics_driver/wrapper.tzst not bundled in app assets (have: $present); " +
                "stage it from WinNative checkout (see docs/04-ASSET-MANIFEST.md §2.1)",
            "wrapper.tzst" in present,
        )

        val source = newSource()
        val resolved = source.resolve(ContentComponent.TURNIP.id)

        assertTrue("expected Resolved artifact", resolved is ContentArtifact.Resolved)
        resolved as ContentArtifact.Resolved
        assertEquals(ContentComponent.TURNIP, resolved.component)
        assertEquals("1", resolved.version)
        assertTrue("extracted dir missing: ${resolved.path}", resolved.path.isDirectory)
        assertTrue(
            "extracted dir empty (wrapper.tzst extract produced nothing): ${resolved.path}",
            (resolved.path.list()?.isNotEmpty() == true),
        )
        // wrapper.tzst ships the Mesa Vulkan ICD wrapper libs + json.
        val allFiles = resolved.path.walkTopDown().map { it.name }.toList()
        assertTrue(
            "wrapper_icd.aarch64.json not found in extract: ${resolved.path}",
            allFiles.any { it.contains("wrapper_icd") },
        )
        println("TURNIP_RESOLVED path=${resolved.path} files=${allFiles.size}")
    }

    @Test
    fun resolve_wine_wcp_installsLocally() = runBlocking {
        val topAssets = appCtx.assets.list("").orEmpty().toList()
        assumeTrue(
            "Proton-10.0-4-x86_64.wcp not bundled in app assets (have: $topAssets); " +
                "stage it (see docs/04-ASSET-MANIFEST.md §5). This is the production " +
                "replacement for the test's host curl+adb push workaround.",
            "Proton-10.0-4-x86_64.wcp" in topAssets,
        )

        val source = newSource()
        val resolved = source.resolve(ContentComponent.WINE.id)

        assertTrue("expected Resolved artifact", resolved is ContentArtifact.Resolved)
        resolved as ContentArtifact.Resolved
        assertEquals(ContentComponent.WINE, resolved.component)
        assertEquals("Proton-10.0-4-x86_64-0", resolved.version)
        // Install dir = filesDir/contents/Proton/10.0-4-x86_64-0 (ContentsManager.getInstallDir).
        assertTrue("proton install dir missing: ${resolved.path}", resolved.path.isDirectory)
        assertTrue("proton bin/ missing: ${resolved.path}", File(resolved.path, "bin").isDirectory)
        assertTrue(
            "proton prefixPack missing: ${resolved.path}",
            resolved.path.walkTopDown().any { it.name.startsWith("prefixPack") },
        )
        println("WINE_RESOLVED path=${resolved.path} version=${resolved.version}")
    }

    private fun newSource(): BundledContentSource {
        val manifest = ContentManifest.load(appCtx)
        val installer = WinlatorBundledAssetInstaller(appCtx)
        return BundledContentSource(appCtx, manifest, installer, DefaultDispatcherProvider())
    }
}
