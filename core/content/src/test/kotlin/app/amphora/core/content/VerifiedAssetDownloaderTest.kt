package app.amphora.core.content

import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedAssetDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifiedPersistentAssetSkipsNetwork() = runBlocking {
        val root = temporaryFolder.newFolder("assets")
        val payload = "already downloaded".toByteArray()
        val sha =
            MessageDigest
                .getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }
        val asset = root.resolve("nested/runtime.bin")
        requireNotNull(asset.parentFile).mkdirs()
        asset.writeBytes(payload)
        AssetDigest.markerFor(asset).writeText(sha)

        val resolved =
            VerifiedAssetDownloader(DefaultDispatcherProvider()).acquire(
                root = root,
                relativePath = "nested/runtime.bin",
                remoteUrl = "https://127.0.0.1:1/must-not-be-requested",
                expectedSha256 = sha,
                expectedSize = payload.size.toLong(),
            )

        assertEquals(asset.canonicalFile, resolved.canonicalFile)
        assertEquals(payload.toList(), resolved.readBytes().toList())
        assertEquals(payload.size.toLong(), AssetDigest.pinnedSize(resolved))
    }

    @Test
    fun offlinePinUpdatePreservesLastKnownGoodDestination() = runBlocking {
        val root = temporaryFolder.newFolder("offline-update")
        val oldPayload = "last known good".toByteArray()
        val newPayload = "new release".toByteArray()
        val oldSha = sha256(oldPayload)
        val newSha = sha256(newPayload)
        val asset = root.resolve("runtime.bin")
        asset.writeBytes(oldPayload)
        AssetDigest.writePin(asset, oldSha)

        try {
            VerifiedAssetDownloader(DefaultDispatcherProvider()).acquire(
                root = root,
                relativePath = "runtime.bin",
                remoteUrl = "https://127.0.0.1:1/unreachable",
                expectedSha256 = newSha,
                expectedSize = newPayload.size.toLong(),
            )
            throw AssertionError("offline download unexpectedly succeeded")
        } catch (_: IOException) {
            // Expected after bounded retries.
        }

        assertEquals(oldPayload.toList(), asset.readBytes().toList())
        assertEquals(oldSha, AssetDigest.pinnedSha(asset))
        assertTrue(AssetDigest.hasCurrentRecord(asset))
    }

    @Test
    fun legacyDigestOnlyPinIsUpgradedAndPreservedWhenNewPinIsOffline() = runBlocking {
        val root = temporaryFolder.newFolder("legacy-offline-update")
        val oldPayload = "legacy last known good".toByteArray()
        val newPayload = "new release".toByteArray()
        val oldSha = sha256(oldPayload)
        val asset = root.resolve("runtime.bin")
        asset.writeBytes(oldPayload)
        AssetDigest.markerFor(asset).writeText(oldSha)

        try {
            VerifiedAssetDownloader(DefaultDispatcherProvider()).acquire(
                root = root,
                relativePath = "runtime.bin",
                remoteUrl = "https://127.0.0.1:1/unreachable",
                expectedSha256 = sha256(newPayload),
                expectedSize = newPayload.size.toLong(),
            )
            throw AssertionError("offline download unexpectedly succeeded")
        } catch (_: IOException) {
            // Expected after bounded retries.
        }

        assertEquals(oldPayload.toList(), asset.readBytes().toList())
        assertEquals(oldSha, AssetDigest.pinnedSha(asset))
        assertEquals(oldPayload.size.toLong(), AssetDigest.pinnedSize(asset))
        assertTrue(AssetDigest.hasCurrentRecord(asset))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        runBlocking {
            VerifiedAssetDownloader(DefaultDispatcherProvider()).acquire(
                root = temporaryFolder.root,
                relativePath = "../escape",
                remoteUrl = "https://example.invalid/escape",
                expectedSha256 = "0".repeat(64),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlainHttp() {
        runBlocking {
            VerifiedAssetDownloader(DefaultDispatcherProvider()).acquire(
                root = temporaryFolder.root,
                relativePath = "asset",
                remoteUrl = "http://example.invalid/asset",
                expectedSha256 = "0".repeat(64),
            )
        }
    }

    private fun sha256(payload: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }
}
