package app.amphora.core.content

import app.amphora.core.common.dispatcher.DispatcherProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
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
        require(AssetDigest.HEX.matches(expectedSha256.lowercase())) {
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
            // A complete .part that already matches the pin must not Range-resume
            // (bytes=<size>- → HTTP 416) and then get deleted.
            if (promoteIfShaMatches(partial, destination, expectedSha256, expectedSize, relativePath)) {
                return@withLock destination
            }
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
                    if (!promoteIfShaMatches(partial, destination, expectedSha256, expectedSize, relativePath)) {
                        val actual = AssetDigest.of(partial)
                        val actualSize = partial.length()
                        partial.delete()
                        throw SecurityException(
                            "SHA-256 mismatch for $relativePath: expected=$expectedSha256 actual=$actual" +
                                sizeHint(expectedSize, actualSize),
                        )
                    }
                    return@withLock destination
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    lastFailure = failure
                    android.util.Log.w(
                        TAG,
                        "Download attempt ${attempt + 1}/$MAX_ATTEMPTS failed for $relativePath",
                        failure,
                    )
                    if (failure is SecurityException) {
                        partial.delete()
                    } else if (isUnsatisfiableRange(failure)) {
                        // CDN file ends at partial.length(); accept if digest matches.
                        if (promoteIfShaMatches(
                                partial,
                                destination,
                                expectedSha256,
                                expectedSize,
                                relativePath,
                            )
                        ) {
                            return@withLock destination
                        }
                        partial.delete()
                    }
                    if (attempt + 1 < MAX_ATTEMPTS) {
                        Thread.sleep(RETRY_DELAYS_MS[attempt])
                    }
                }
            }
            val reason = lastFailure?.message ?: lastFailure?.javaClass?.simpleName ?: "unknown error"
            throw IOException(
                "Unable to download $relativePath after $MAX_ATTEMPTS attempts: $reason",
                lastFailure,
            )
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
        if (!file.isFile) {
            AssetDigest.markerFor(file).delete()
            return false
        }
        if (AssetDigest.matchesPin(file, expectedSha256)) {
            warnIfSizePinDiffers(file, expectedSize)
            return true
        }

        // A valid record for a different pin identifies the last-known-good asset.
        // Do not hash or delete it before its replacement has downloaded and verified.
        if (AssetDigest.hasCurrentRecord(file)) return false

        // Missing/malformed markers are not trusted. Hash only this recovery path,
        // then record metadata so later startup checks remain O(1).
        val valid = AssetDigest.of(file).equals(expectedSha256, ignoreCase = true)
        if (valid) {
            warnIfSizePinDiffers(file, expectedSize)
            AssetDigest.writePin(file, expectedSha256)
        }
        return valid
    }

    private fun warnIfSizePinDiffers(file: File, expectedSize: Long?) {
        if (expectedSize == null || file.length() == expectedSize) return
        android.util.Log.w(
            TAG,
            "Keeping $file despite size pin mismatch " +
                "(manifest=$expectedSize actual=${file.length()})",
        )
    }

    /**
     * If [partial] digests to [expectedSha256], publish it to [destination].
     * Size pins are advisory — stale sizes after a release bump must not block.
     */
    private fun promoteIfShaMatches(
        partial: File,
        destination: File,
        expectedSha256: String,
        expectedSize: Long?,
        relativePath: String,
    ): Boolean {
        if (!partial.isFile || partial.length() <= 0L) return false
        progressBus?.update(
            ProvisionProgress(
                stage = "verify",
                detail = relativePath,
                bytesDownloaded = partial.length(),
                totalBytes = expectedSize ?: partial.length(),
            ),
        )
        val actual = AssetDigest.of(partial)
        if (!actual.equals(expectedSha256, ignoreCase = true)) return false
        if (expectedSize != null && partial.length() != expectedSize) {
            android.util.Log.w(
                TAG,
                "Size pin mismatch for $relativePath: manifest=$expectedSize " +
                    "actual=${partial.length()}; accepting SHA match",
            )
        }
        AtomicFilePublisher.replace(partial, destination)
        AssetDigest.writePin(destination, expectedSha256)
        return true
    }

    private fun downloadOnce(
        remoteUrl: String,
        partial: File,
        expectedSize: Long?,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ) {
        val existing = partial.length()
        // Already at (or past) the size pin: a Range resume would 416. Caller
        // should have promoted via SHA; if we still get here, force a full GET.
        val connection = URI(remoteUrl).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept-Encoding", "identity")
        val resume =
            existing > 0L && (expectedSize == null || existing < expectedSize)
        if (resume) connection.setRequestProperty("Range", "bytes=$existing-")
        android.util.Log.i(
            TAG,
            "GET ${safeUrl(remoteUrl)} resume=$existing agent=${System.getProperty("http.agent")}",
        )
        try {
            val response = connection.responseCode
            android.util.Log.i(
                TAG,
                "HTTP $response ${safeUrl(connection.url.toString())} " +
                    "length=${connection.contentLengthLong}",
            )
            val append = resume && response == HttpURLConnection.HTTP_PARTIAL
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

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000)
        private const val TAG = "VerifiedAssetDownloader"

        private fun safeUrl(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return "<invalid-url>"
            return URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }

        private fun sizeHint(expectedSize: Long?, actualSize: Long): String =
            if (expectedSize == null || expectedSize == actualSize) {
                ""
            } else {
                " (manifest size=$expectedSize actual=$actualSize)"
            }

        private fun isUnsatisfiableRange(failure: Throwable): Boolean {
            val message = failure.message.orEmpty()
            return message.contains("HTTP 416") ||
                message.contains("Range Not Satisfiable", ignoreCase = true)
        }
    }
}
