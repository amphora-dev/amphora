package app.amphora.sdk.logs

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.logs.LogFileProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

public data class LogFile(
    /** The session id. */
    val id: String,
    val file: File,
    val bytes: Long,
    val modifiedAt: Long,
)

/** One log per session: Wine's output plus `amphora:` lines from the SDK. */
public class LogStore internal constructor(
    private val context: Context,
    private val paths: AmphoraPaths,
    private val io: CoroutineContext,
) {
    public suspend fun list(): List<LogFile> = withContext(io) {
        (paths.logs.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .map { LogFile(it.name.removeSuffix(SUFFIX), it, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }
    }

    public fun file(id: String): File = paths.log(id)

    /**
     * A content URI for sharing the log with another app (`ACTION_SEND` with
     * `FLAG_GRANT_READ_URI_PERMISSION`); only session logs are reachable.
     */
    public fun shareUri(id: String): Uri =
        FileProvider.getUriForFile(context, LogFileProvider.authority(context.packageName), file(id))

    /** The last [maxLines] lines, reading at most [maxBytes] from the end. */
    public suspend fun tail(id: String, maxLines: Int = 500, maxBytes: Int = 256 * 1024): List<String> =
        withContext(io) { tailOf(file(id), maxLines, maxBytes) }

    public suspend fun delete(id: String) {
        withContext(io) { file(id).delete() }
    }

    public suspend fun clear() {
        withContext(io) { paths.logs.listFiles()?.forEach { if (it.name.endsWith(SUFFIX)) it.delete() } }
    }

    /** Keeps the [keep] newest logs. */
    internal suspend fun prune(keep: Int = KEEP) {
        list().drop(keep).forEach { log ->
            if (!log.file.delete()) AmphoraLog.w("could not delete ${log.file}")
        }
    }

    internal companion object {
        const val SUFFIX = ".log"
        const val KEEP = 20

        /** Reads the end of a file that may be growing. A partial first line is dropped. */
        fun tailOf(file: File, maxLines: Int, maxBytes: Int): List<String> {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val length = raf.length()
                    val start = (length - maxBytes).coerceAtLeast(0)
                    val bytes = ByteArray((length - start).toInt())
                    raf.seek(start)
                    raf.readFully(bytes)
                    var lines = bytes.decodeToString().split('\n')
                    if (start > 0) {
                        // The first line is partial unless the window starts right after a newline.
                        raf.seek(start - 1)
                        if (raf.read() != '\n'.code) lines = lines.drop(1)
                    }
                    if (lines.lastOrNull()?.isEmpty() == true) lines = lines.dropLast(1)
                    return lines.takeLast(maxLines).map { it.trimEnd('\r') }
                }
            } catch (_: IOException) {
                return emptyList()
            }
        }
    }
}
