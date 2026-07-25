package app.amphora.core.content

import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class VerifiedAssetDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifiedPersistentAssetSkipsNetwork() = runBlocking {
        val root = temporaryFolder.newFolder("assets")
        val payload = "already downloaded".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        val asset = root.resolve("nested/runtime.bin")
        asset.parentFile.mkdirs()
        asset.writeBytes(payload)
        root.resolve("nested/runtime.bin.sha256").writeText(sha)

        val resolved = VerifiedAssetDownloader(DefaultDispatcherProvider()).acquire(
            root = root,
            relativePath = "nested/runtime.bin",
            remoteUrl = "https://127.0.0.1:1/must-not-be-requested",
            expectedSha256 = sha,
            expectedSize = payload.size.toLong(),
        )

        assertEquals(asset.canonicalFile, resolved.canonicalFile)
        assertEquals(payload.toList(), resolved.readBytes().toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() = runBlocking {
        VerifiedAssetDownloader(DefaultDispatcherProvider()).acquire(
            root = temporaryFolder.root,
            relativePath = "../escape",
            remoteUrl = "https://example.invalid/escape",
            expectedSha256 = "0".repeat(64),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlainHttp() = runBlocking {
        VerifiedAssetDownloader(DefaultDispatcherProvider()).acquire(
            root = temporaryFolder.root,
            relativePath = "asset",
            remoteUrl = "http://example.invalid/asset",
            expectedSha256 = "0".repeat(64),
        )
    }
}
