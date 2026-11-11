package app.amphora.sdk.internal.io

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Lowercase hex sha256 of a file, reporting bytes hashed. Cancellable. */
internal suspend fun sha256(file: File, onProgress: (Long) -> Unit = {}): String {
    val context = currentCoroutineContext()
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(1024 * 1024)
        var done = 0L
        while (true) {
            context.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            done += n
            onProgress(done)
        }
    }
    return digest.digest().toHex()
}

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { i, byte ->
        val v = byte.toInt() and 0xff
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
