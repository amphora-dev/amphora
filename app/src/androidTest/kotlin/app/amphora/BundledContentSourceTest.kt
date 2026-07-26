package app.amphora

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.ContentSource
import app.amphora.core.content.model.ContentArtifact
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.id
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * Real-device verification of the production remote [ContentSource].
 *
 * A cold run downloads and SHA-verifies each component; a warm run resolves the
 * installed component without network access. No large APK assets are required.
 *
 * Device: Lenovo TB322FC, arm64-v8a, API 36, Adreno 830.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RemoteContentSourceTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var manifest: ContentManifest

    @Inject
    lateinit var source: ContentSource

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun manifest_loadsAndParsesAllEntries() {
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
    fun resolve_turnip_archive_installsWithShaVerify() = runBlocking {
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
    fun resolve_wine_wcp_installsFromRemoteSource() = runBlocking {
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
}
