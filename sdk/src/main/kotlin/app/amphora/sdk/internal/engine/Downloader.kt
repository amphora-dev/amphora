package app.amphora.sdk.internal.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The server answered, but not with the file. Not retried. */
internal class HttpStatusException(val status: Int) : IOException("HTTP $status")

/**
 * Resumable HTTP download into a `.part` file. Transient failures retry with
 * backoff and resume from what is on disk; cancelling the coroutine aborts the
 * connection promptly instead of waiting for a read timeout.
 */
internal class Downloader(
    private val userAgent: String,
    private val io: CoroutineContext,
    private val attempts: Int = 5,
) {
    /**
     * Downloads [url] to [dest] (which must not be read until this returns),
     * reporting cumulative bytes. Returns when [dest] has exactly [size] bytes.
     */
    suspend fun download(url: String, dest: File, size: Long, onProgress: (done: Long) -> Unit) {
        dest.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (dest.length() > size) dest.delete()
            if (dest.length() == size) return
            try {
                withContext(io) { fetch(url, dest, size, onProgress) }
                if (dest.length() == size) return
                throw IOException("connection closed at ${dest.length()} of $size bytes")
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                attempt++
                if (attempt >= attempts) throw e
                delay(1000L shl (attempt - 1))
            }
        }
    }

    private suspend fun fetch(url: String, dest: File, size: Long, onProgress: (Long) -> Unit) {
        val offset = dest.length()
        val conn = URL(url).openConnection() as HttpURLConnection
        val job = currentCoroutineContext()[Job]
        val abort = job?.invokeOnCompletion { conn.disconnect() }
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val append =
                when (val status = conn.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> true
                    HttpURLConnection.HTTP_OK -> false
                    416 -> return // Range not satisfiable: the caller re-checks the length.
                    else -> throw HttpStatusException(status)
                }
            if (!append) dest.delete()
            conn.inputStream.use { input -> copy(input, dest, append, onProgress) }
        } finally {
            abort?.dispose()
            conn.disconnect()
        }
    }

    private suspend fun copy(input: InputStream, dest: File, append: Boolean, onProgress: (Long) -> Unit) {
        val context = currentCoroutineContext()
        FileOutputStream(dest, append).use { out ->
            var done = dest.length()
            val buffer = ByteArray(256 * 1024)
            while (true) {
                context.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                done += n
                onProgress(done)
            }
            out.fd.sync()
        }
    }
}
