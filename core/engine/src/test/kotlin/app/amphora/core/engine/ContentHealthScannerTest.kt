package app.amphora.core.engine

import app.amphora.core.content.AssetDigest
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.RuntimeAssetLocalOverride
import app.amphora.core.content.model.ContentComponent
import app.amphora.core.content.model.ManifestEntry
import app.amphora.core.engine.model.ContentComponentHealth
import app.amphora.core.engine.model.RuntimeAssetHealth
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentHealthScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun componentScanPreservesPinLabelsAndWcpFallback() = runBlocking {
        val runtimeAssets = temporaryFolder.newFolder("runtime-assets")
        val residue = File(temporaryFolder.root, "imagefs.olddead")
        val box64Directory =
            temporaryFolder.newFolder("contents", "Box64").apply {
                resolve("Box64-0.4.5-local").mkdir()
                resolve("Box64-0.4.4-0").mkdir()
                resolve("README").writeText("not an install")
            }
        val emptyDxvkDirectory = temporaryFolder.newFolder("contents", "DXVK")
        val installedComponents = setOf(ContentComponent.WINE, ContentComponent.VKD3D)
        val requestedContentTypes = mutableListOf<String>()
        val scanner =
            ContentHealthScanner(
                runtimeAssetsDirectory = runtimeAssets,
                imageFsResidue = residue,
                contentTypeDirectoryResolver =
                ContentHealthScanner.ContentTypeDirectoryResolver { contentType ->
                    requestedContentTypes += contentType
                    when (contentType) {
                        "Box64" -> box64Directory
                        "DXVK" -> emptyDxvkDirectory
                        else -> null
                    }
                },
                currentRootfsVersion = { "41" },
                isComponentInstalled = { it.component in installedComponents },
            )

        val snapshot = scanner.scan(manifest())

        assertEquals(
            ContentComponentHealth(
                component = ContentComponent.ROOTFS,
                pinned = "42",
                installed = "41",
                state = ContentComponentHealth.State.UPDATE,
            ),
            snapshot.component(ContentComponent.ROOTFS),
        )
        assertEquals(
            ContentComponentHealth(
                component = ContentComponent.WINE,
                pinned = "11.0",
                installed = "11.0",
                state = ContentComponentHealth.State.READY,
            ),
            snapshot.component(ContentComponent.WINE),
        )
        assertEquals(
            ContentComponentHealth(
                component = ContentComponent.BOX64,
                pinned = "0.4.5",
                installed = "Box64-0.4.4-0, Box64-0.4.5-local",
                state = ContentComponentHealth.State.UPDATE,
            ),
            snapshot.component(ContentComponent.BOX64),
        )
        assertEquals(
            ContentComponentHealth(
                component = ContentComponent.DXVK,
                pinned = "2.7.1",
                installed = null,
                state = ContentComponentHealth.State.MISSING,
            ),
            snapshot.component(ContentComponent.DXVK),
        )
        assertEquals(
            ContentComponentHealth(
                component = ContentComponent.VKD3D,
                pinned = "1.17",
                installed = "1.17",
                state = ContentComponentHealth.State.READY,
            ),
            snapshot.component(ContentComponent.VKD3D),
        )
        assertEquals(listOf("Box64", "DXVK"), requestedContentTypes.distinct())
        assertFalse(snapshot.imageFsResidue)
    }

    @Test
    fun runtimeAssetScanReportsEveryPinAndOverrideState() = runBlocking {
        val runtimeAssets = temporaryFolder.newFolder("runtime-assets")
        val residue =
            File(temporaryFolder.root, "imagefs.olddead").apply {
                writeText("stale")
            }
        val expectedSha = "a".repeat(64)
        val otherSha = "b".repeat(64)
        val localSha = "f".repeat(64)

        runtimeAssets.resolve("ready.bin").writePinned("data", expectedSha)
        runtimeAssets.resolve("mismatch.bin").writePinned("data", otherSha)
        runtimeAssets.resolve("wrong-size.bin").writePinned("data", expectedSha)
        runtimeAssets.resolve("unverified.bin").writeText("data")
        runtimeAssets.resolve("local.bin").apply {
            writePinned("local", localSha)
            File(absolutePath + RuntimeAssetLocalOverride.SUFFIX).writeText(localSha)
        }
        val scanner =
            ContentHealthScanner(
                runtimeAssetsDirectory = runtimeAssets,
                imageFsResidue = residue,
                contentTypeDirectoryResolver =
                ContentHealthScanner.ContentTypeDirectoryResolver { null },
                currentRootfsVersion = { "42" },
                isComponentInstalled = { true },
            )
        val manifest =
            manifest(
                runtimeAssets =
                listOf(
                    RuntimeAsset("ready.bin", expectedSha, 4),
                    RuntimeAsset("missing.bin", expectedSha, 4),
                    RuntimeAsset("mismatch.bin", expectedSha, 4),
                    RuntimeAsset("wrong-size.bin", expectedSha, 5),
                    RuntimeAsset("unverified.bin", expectedSha, 4),
                    RuntimeAsset("local.bin", expectedSha, 5),
                ),
            )

        val snapshot = scanner.scan(manifest)

        assertEquals(RuntimeAssetHealth.State.READY, snapshot.asset("ready.bin").state)
        assertEquals(RuntimeAssetHealth.State.MISSING, snapshot.asset("missing.bin").state)
        assertEquals(RuntimeAssetHealth.State.MISMATCH, snapshot.asset("mismatch.bin").state)
        assertEquals(RuntimeAssetHealth.State.MISMATCH, snapshot.asset("wrong-size.bin").state)
        assertEquals(RuntimeAssetHealth.State.UNVERIFIED, snapshot.asset("unverified.bin").state)
        assertEquals(RuntimeAssetHealth.State.LOCAL_OVERRIDE, snapshot.asset("local.bin").state)
        assertEquals(localSha, snapshot.asset("local.bin").installedSha)
        assertEquals(expectedSha, snapshot.asset("local.bin").pinnedSha)
        assertTrue(snapshot.asset("local.bin").healthy)
        assertFalse(snapshot.asset("mismatch.bin").healthy)
        assertTrue(snapshot.imageFsResidue)
    }

    private fun app.amphora.core.engine.model.ContentHealthSnapshot.component(
        component: ContentComponent,
    ): ContentComponentHealth = components.single { it.component == component }

    private fun app.amphora.core.engine.model.ContentHealthSnapshot.asset(path: String): RuntimeAssetHealth =
        runtimeAssets.single { it.assetPath == path }

    private fun File.writePinned(contents: String, sha256: String) {
        parentFile?.mkdirs()
        writeText(contents)
        File(absolutePath + AssetDigest.SHA_SUFFIX).writeText("$sha256\n${length()}\n")
    }

    private fun manifest(runtimeAssets: List<RuntimeAsset> = emptyList()): ContentManifest {
        val runtimeJson =
            runtimeAssets.joinToString(",") {
                """
                {
                  "assetPath": "${it.path}",
                  "sha256": "${it.sha256}",
                  "remoteUrl": "https://example.com/${it.path}",
                  "size": ${it.size}
                }
                """.trimIndent()
            }
        return ContentManifest.parse(
            """
            {
              "version": 1,
              "components": {
                "rootfs": {
                  "kind": "ROOTFS",
                  "assetPath": "rootfs.tzst",
                  "sha256": "$COMPONENT_SHA",
                  "version": "42",
                  "remoteUrl": "https://example.com/rootfs.tzst"
                },
                "wine": {
                  "kind": "WCP",
                  "assetPath": "wine.wcp",
                  "sha256": "$COMPONENT_SHA",
                  "version": "Proton-11.0-amphora-x86_64-1",
                  "contentType": "Proton",
                  "verName": "11.0",
                  "verCode": 1,
                  "remoteUrl": "https://example.com/wine.wcp"
                },
                "box64": {
                  "kind": "WCP",
                  "assetPath": "box64.wcp",
                  "sha256": "$COMPONENT_SHA",
                  "version": "Box64-0.4.5-0",
                  "contentType": "Box64",
                  "verName": "0.4.5",
                  "verCode": 0,
                  "remoteUrl": "https://example.com/box64.wcp"
                },
                "dxvk": {
                  "kind": "WCP",
                  "assetPath": "dxvk.wcp",
                  "sha256": "$COMPONENT_SHA",
                  "version": "DXVK-2.7.1-0",
                  "contentType": "DXVK",
                  "verName": "2.7.1",
                  "verCode": 0,
                  "remoteUrl": "https://example.com/dxvk.wcp"
                },
                "dxvk_sarek": {
                  "kind": "WCP",
                  "assetPath": "dxvk-sarek.wcp",
                  "sha256": "$COMPONENT_SHA",
                  "version": "DXVK-1.11-sarek-0",
                  "contentType": "DXVK",
                  "verName": "1.11-sarek",
                  "verCode": 0,
                  "remoteUrl": "https://example.com/dxvk-sarek.wcp"
                },
                "vkd3d": {
                  "kind": "WCP",
                  "assetPath": "vkd3d.wcp",
                  "sha256": "$COMPONENT_SHA",
                  "version": "VKD3D-1.17-0",
                  "contentType": "VKD3D",
                  "verName": "1.17",
                  "verCode": 0,
                  "remoteUrl": "https://example.com/vkd3d.wcp"
                }
              },
              "runtimeAssets": [$runtimeJson]
            }
            """.trimIndent(),
        )
    }

    private data class RuntimeAsset(val path: String, val sha256: String, val size: Long)

    private companion object {
        val COMPONENT_SHA = "c".repeat(64)
    }
}
