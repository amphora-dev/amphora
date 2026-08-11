package app.amphora.buildlogic

import java.io.File
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentStagingPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun manifestIncludesComponentsAndRuntimeAssetsButNotRootfs() {
        val parsed =
            parseStagingManifest(
                """
                {
                  "wcpCatalogUrl": "https://example.test/catalog.json",
                  "components": {
                    "wine": {
                      "kind": "WCP",
                      "assetPath": "wine.wcp",
                      "sha256": "${"0".repeat(64)}",
                      "size": 10
                    },
                    "rootfs": {
                      "kind": "ROOTFS",
                      "assetPath": "imagefs.txz",
                      "sha256": "${"1".repeat(64)}",
                      "size": 20,
                      "remoteUrl": "https://example.test/imagefs.txz"
                    }
                  },
                  "runtimeAssets": [{
                    "assetPath": "graphics_driver/wrapper.tzst",
                    "sha256": "${"2".repeat(64)}",
                    "size": 30,
                    "remoteUrl": "https://example.test/wrapper.tzst"
                  }]
                }
                """.trimIndent(),
            )

        assertEquals(listOf("wine.wcp", "graphics_driver/wrapper.tzst"), parsed.assets.map { it.assetPath })
        assertTrue(parsed.assets.first().catalogEligible)
        assertFalse(parsed.assets.last().catalogEligible)
    }

    @Test
    fun syncRemovesFilesThatAreNoLongerInManifest() {
        val root = temporaryFolder.newFolder("exact-sync")
        val localRoot = File(root, "winnative").apply { mkdirs() }
        val output = File(root, "generated")
        val cache = File(root, "cache")
        File(output, "stale/old.tzst").apply {
            parentFile.mkdirs()
            writeText("stale")
        }
        val bytes = "verified asset".toByteArray()
        File(localRoot, "runtime/current.tzst").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
        val asset = asset("runtime/current.tzst", bytes)

        stager(localRoot, output, cache).sync(listOf(asset))

        assertFalse(File(output, "stale/old.tzst").exists())
        assertTrue(File(output, asset.assetPath).isFile)
        assertEquals(bytes.toList(), File(output, asset.assetPath).readBytes().toList())
    }

    @Test
    fun sameSizeWrongShaFailsAndPreservesPreviousOutput() {
        val root = temporaryFolder.newFolder("sha-mismatch")
        val localRoot = File(root, "winnative").apply { mkdirs() }
        val output = File(root, "generated").apply { mkdirs() }
        val cache = File(root, "cache")
        File(output, "previous.txt").writeText("keep")
        val expected = "good".toByteArray()
        File(localRoot, "asset.bin").writeBytes("evil".toByteArray())

        val failure =
            assertThrows(GradleException::class.java) {
                stager(localRoot, output, cache).sync(listOf(asset("asset.bin", expected)))
            }

        assertTrue(failure.message.orEmpty().contains("SHA-256 mismatch"))
        assertEquals("keep", File(output, "previous.txt").readText())
        assertFalse(File(output, "asset.bin").exists())
    }

    @Test
    fun missingWinNativeAssetDownloadsAndVerifiesRemoteUrl() {
        val root = temporaryFolder.newFolder("remote-fallback")
        val output = File(root, "generated")
        val bytes = "downloaded".toByteArray()
        val requestedUrls = mutableListOf<String>()
        val remoteAsset =
            asset("wincomponents/direct3d.tzst", bytes)
                .copy(remoteUrl = "https://example.test/direct3d.tzst")

        ExactContentStager(
            winnativeDir = File(root, "missing-winnative"),
            outputDir = output,
            cacheDir = File(root, "cache"),
            downloader =
                AssetDownloader { url, destination ->
                    requestedUrls += url
                    destination.writeBytes(bytes)
                },
        ).sync(listOf(remoteAsset))

        assertEquals(listOf(remoteAsset.remoteUrl), requestedUrls)
        assertEquals(bytes.toList(), File(output, remoteAsset.assetPath).readBytes().toList())
    }

    @Test
    fun mismatchedDownloadFailsInsteadOfPublishingPartialOutput() {
        val root = temporaryFolder.newFolder("remote-mismatch")
        val expected = "good".toByteArray()
        val remoteAsset =
            asset("fonts.tzst", expected)
                .copy(remoteUrl = "https://example.test/fonts.tzst")

        val failure =
            assertThrows(GradleException::class.java) {
                ExactContentStager(
                    winnativeDir = null,
                    outputDir = File(root, "generated"),
                    cacheDir = File(root, "cache"),
                    downloader = AssetDownloader { _, destination -> destination.writeBytes("evil".toByteArray()) },
                ).sync(listOf(remoteAsset))
            }

        assertTrue(failure.message.orEmpty().contains("SHA-256 mismatch"))
        assertFalse(File(root, "generated").exists())
    }

    @Test
    fun missingLocalAndRemoteSourceFails() {
        val root = temporaryFolder.newFolder("missing-source")
        val bytes = "asset".toByteArray()

        val failure =
            assertThrows(GradleException::class.java) {
                stager(null, File(root, "generated"), File(root, "cache"))
                    .sync(listOf(asset("asset.bin", bytes)))
            }

        assertTrue(failure.message.orEmpty().contains("has no verified remote URL"))
    }

    private fun stager(localRoot: File?, output: File, cache: File) =
        ExactContentStager(
            winnativeDir = localRoot,
            outputDir = output,
            cacheDir = cache,
            downloader = AssetDownloader { _, _ -> error("unexpected download") },
        )

    private fun asset(path: String, bytes: ByteArray) =
        StagingAsset(
            id = "test:$path",
            assetPath = path,
            sha256 =
                temporaryFolder.newFile().run {
                    writeBytes(bytes)
                    sha256(this)
                },
            size = bytes.size.toLong(),
            remoteUrl = null,
            catalogEligible = false,
        )
}
