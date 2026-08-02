package app.amphora.core.content

import app.amphora.core.common.dispatcher.DispatcherProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Resumable, SHA-pinned HTTPS downloader.
 *
 * A download is written to `<destination>.part`, verified, then atomically
 * renamed. A sidecar marker makes subsequent startup checks O(1); size and the
 * pinned digest are still checked before trusting it.
 */
class VerifiedAssetDownloader(
    private val dispatchers: DispatcherProvider,
    private val progressBus: ProvisionProgressBus? = null,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun acquire(
        root: File,
        relativePath: String,
        remoteUrl: String,
        expectedSha256: String,
        expectedSize: Long? = null,
        label: String = relativePath,
    ): File = withContext(dispatchers.io) {
        require(expectedSha256.matches(SHA_PATTERN)) {
            "A pinned SHA-256 is required for $relativePath"
        }
        require(URI(remoteUrl).scheme.equals("https", ignoreCase = true)) {
            "Only HTTPS asset URLs are allowed: $remoteUrl"
        }

        val destination = safeDestination(root, relativePath)
        val lock = locks.getOrPut(destination.absolutePath) { Mutex() }
        lock.withLock {
            if (isVerified(destination, expectedSha256, expectedSize)) {
                return@withLock destination
            }

            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + PART_SUFFIX)
            var lastFailure: Throwable? = null
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    downloadOnce(remoteUrl, partial, expectedSize) { bytes, total ->
                        progressBus?.update(
                            ProvisionProgress(
                                stage = "download",
                                detail = label,
                                bytesDownloaded = bytes,
                                totalBytes = total,
                            ),
                        )
                    }
                    if (expectedSize != null && partial.length() != expectedSize) {
                        throw IOException(
                            "Truncated asset $relativePath: expected $expectedSize bytes, got ${partial.length()}",
                        )
                    }
                    progressBus?.update(
                        ProvisionProgress(
                            stage = "verify",
                            detail = label,
                            bytesDownloaded = partial.length(),
                            totalBytes = expectedSize ?: partial.length(),
                        ),
                    )
                    val actual = sha256(partial)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        partial.delete()
                        throw SecurityException(
                            "SHA-256 mismatch for $relativePath: expected=$expectedSha256 actual=$actual",
                        )
                    }
                    atomicReplace(partial, destination)
                    markerFor(destination).writeText(expectedSha256.lowercase())
                    return@withLock destination
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    lastFailure = failure
                    if (failure is SecurityException) partial.delete()
                    if (attempt + 1 < MAX_ATTEMPTS) {
                        Thread.sleep(RETRY_DELAYS_MS[attempt])
                    }
                }
            }
            throw IOException("Unable to download $relativePath after $MAX_ATTEMPTS attempts", lastFailure)
        }
    }

    private fun safeDestination(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
            "Asset path must be relative: $relativePath"
        }
        val canonicalRoot = root.canonicalFile
        val destination = File(canonicalRoot, relativePath).canonicalFile
        require(destination.path.startsWith(canonicalRoot.path + File.separator)) {
            "Asset path escapes destination root: $relativePath"
        }
        return destination
    }

    private fun isVerified(file: File, expectedSha256: String, expectedSize: Long?): Boolean {
        if (!file.isFile || (expectedSize != null && file.length() != expectedSize)) {
            file.delete()
            markerFor(file).delete()
            return false
        }
        val marker = markerFor(file)
        if (marker.isFile && marker.readText().trim().equals(expectedSha256, ignoreCase = true)) {
            return true
        }
        val valid = sha256(file).equals(expectedSha256, ignoreCase = true)
        if (valid) marker.writeText(expectedSha256.lowercase()) else file.delete()
        return valid
    }

    private fun downloadOnce(
        remoteUrl: String,
        partial: File,
        expectedSize: Long?,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ) {
        val existing = partial.length()
        val connection = URI(remoteUrl).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
        try {
            val response = connection.responseCode
            val append = existing > 0 && response == HttpURLConnection.HTTP_PARTIAL
            if (response !in 200..299) {
                throw IOException("HTTP $response ${connection.responseMessage} for $remoteUrl")
            }
            if (!append && existing > 0) {
                partial.delete()
            }
            val startAt = if (append) existing else 0L
            val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
            val total =
                when {
                    expectedSize != null -> expectedSize
                    contentLength != null && append -> startAt + contentLength
                    contentLength != null -> contentLength
                    else -> null
                }
            var written = startAt
            onProgress(written, total)
            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).buffered(BUFFER_SIZE).use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        onProgress(written, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            check(source.renameTo(destination)) {
                "Unable to move verified asset into place: $destination"
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun markerFor(file: File): File = File(file.absolutePath + SHA_SUFFIX)

    private companion object {
        val SHA_PATTERN = Regex("[0-9a-fA-F]{64}")
        const val PART_SUFFIX = ".part"
        const val SHA_SUFFIX = ".sha256"
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000)
    }
}
