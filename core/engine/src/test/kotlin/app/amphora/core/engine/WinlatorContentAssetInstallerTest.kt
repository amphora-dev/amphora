package app.amphora.core.engine

import android.content.Context
import android.content.SharedPreferences
import app.amphora.core.content.InstalledContentPin
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import com.winlator.cmod.runtime.content.ContentProfile
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinlatorContentAssetInstallerTest {
    @Test
    fun reconcileRemovesSupersededWcpAfterPinnedInstallIsReady() {
        withInstaller { installer ->
            val entry = protonEntry()
            val current = installer.resolvedPath(entry).apply { mkdirs() }
            InstalledContentPin.write(current, SHA)
            val stale =
                requireNotNull(current.parentFile)
                    .resolve("10.0-old-x86_64-0")
                    .apply {
                        mkdirs()
                        resolve("payload").writeText("old")
                    }

            assertEquals(1, installer.reconcileToPin(entry, listOf(entry)))

            assertTrue(current.isDirectory)
            assertFalse(stale.exists())
        }
    }

    @Test
    fun reconcileKeepsEveryPinOfTheSameContentType() {
        withInstaller { installer ->
            val entry = dxvkEntry("3.0.2-gplasync", 0)
            val sibling = dxvkEntry("1.11.0-async-sarek", 0)
            val current = installer.resolvedPath(entry).apply { mkdirs() }
            InstalledContentPin.write(current, SHA)
            val siblingDir = installer.resolvedPath(sibling).apply { mkdirs() }
            val stale =
                requireNotNull(current.parentFile)
                    .resolve("2.7.1-old-0")
                    .apply { mkdirs() }

            assertEquals(1, installer.reconcileToPin(entry, listOf(entry, sibling)))

            assertTrue(current.isDirectory)
            assertTrue("the other pinned DXVK must survive", siblingDir.isDirectory)
            assertFalse(stale.exists())
        }
    }

    @Test
    fun reconcileKeepsOldWcpUntilPinnedInstallIsReady() {
        withInstaller { installer ->
            val entry = protonEntry()
            val stale =
                requireNotNull(
                    installer
                        .resolvedPath(entry)
                        .parentFile,
                )
                    .resolve("10.0-old-x86_64-0")
                    .apply { mkdirs() }

            assertEquals(0, installer.reconcileToPin(entry, listOf(entry)))

            assertTrue(stale.isDirectory)
        }
    }

    private fun withInstaller(block: (WinlatorContentAssetInstaller) -> Unit) {
        val root = Files.createTempDirectory("wcp-reconcile-").toFile()
        try {
            val context = mockk<Context>()
            every { context.filesDir } returns root
            every {
                context.getSharedPreferences(any(), any())
            } returns mockk<SharedPreferences>(relaxed = true)
            block(WinlatorContentAssetInstaller(context))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun protonEntry() = ManifestEntry(
        component = ContentComponent.WINE,
        assetPath = "Proton-11.0-current-x86_64.wcp",
        sha256 = SHA,
        version = "Proton-11.0-current-x86_64-1",
        kind = ManifestEntry.Kind.WCP,
        contentType = ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString(),
        verName = "11.0-current-x86_64",
        verCode = 1,
    )

    private fun dxvkEntry(verName: String, verCode: Int) = ManifestEntry(
        component = ContentComponent.DXVK,
        assetPath = "Dxvk-$verName.wcp",
        sha256 = SHA,
        version = "DXVK-$verName-$verCode",
        kind = ManifestEntry.Kind.WCP,
        contentType = ContentProfile.ContentType.CONTENT_TYPE_DXVK.toString(),
        verName = verName,
        verCode = verCode,
    )

    private companion object {
        val SHA = "a".repeat(64)
    }
}
