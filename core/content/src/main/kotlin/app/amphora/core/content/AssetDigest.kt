package app.amphora.core.content

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 and the `.sha256` sidecar convention, in one place.
 *
 * Every provisioned asset is stored next to a sidecar holding its lowercase hex
 * digest and recorded byte size. Later runs trust the marker only while both the
 * destination and recorded size still match, avoiding both missing/truncated-file
 * false positives and re-hashing hundreds of megabytes. The suffix and hashing loop
 * used to be spelled out separately in [VerifiedAssetDownloader],
 * [RuntimeAssetProvisioner], [RuntimeAssetLocalOverride], the launcher and the
 * session preparer — five places that had to agree on a filename for the cache to
 * work at all.
 *
 * `:app:stageBundledContent` hashes the same way but lives in `build-logic`, a
 * separate build with its own classpath, so it cannot share this code.
 */
object AssetDigest {
    /** Sidecar suffix: `<asset>` is pinned by `<asset>.sha256`. */
    const val SHA_SUFFIX = ".sha256"

    /** Matches a canonical lowercase hex SHA-256. */
    val HEX = Regex("^[0-9a-f]{64}$")

    /** The sidecar that pins [assetFile]. */
    fun markerFor(assetFile: File): File = File(assetFile.absolutePath + SHA_SUFFIX)

    /** Digest pinned by [assetFile]'s sidecar, or null when absent or malformed. */
    fun pinnedSha(assetFile: File): String? {
        val firstLine = markerLines(assetFile)?.firstOrNull() ?: return null
        return firstLine.trim().lowercase().takeIf { HEX.matches(it) }
    }

    /** Byte size recorded with the pin, or null for malformed/digest-only markers. */
    fun pinnedSize(assetFile: File): Long? {
        val size = markerLines(assetFile)?.getOrNull(1)?.trim()?.toLongOrNull() ?: return null
        return size.takeIf { it >= 0L }
    }

    /** True when the sidecar still describes the existing file, regardless of its digest. */
    fun hasCurrentRecord(assetFile: File): Boolean = assetFile.isFile &&
        pinnedSha(assetFile) != null &&
        pinnedSize(assetFile) == assetFile.length()

    /** Record [sha256] and the current asset size using an atomic sidecar replacement. */
    fun writePin(assetFile: File, sha256: String) {
        val digest = sha256.trim().lowercase()
        require(HEX.matches(digest)) { "invalid sha256: $sha256" }
        require(assetFile.isFile) { "missing asset: $assetFile" }
        val marker = markerFor(assetFile)
        marker.parentFile?.mkdirs()
        val temporary = File(marker.absolutePath + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write("$digest\n${assetFile.length()}\n".toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            AtomicFilePublisher.replace(temporary, marker)
        } finally {
            temporary.delete()
        }
    }

    /** True when the existing asset's digest and recorded size match the sidecar. */
    fun matchesPin(assetFile: File, expectedSha256: String): Boolean = hasCurrentRecord(assetFile) &&
        pinnedSha(assetFile) == expectedSha256.trim().lowercase()

    fun of(file: File): String = file.inputStream().use { of(it) }

    /** Streams [input] to exhaustion; the caller owns closing it. */
    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.hex()
    }

    /** A digest that the caller fed incrementally (e.g. while tee-ing a download). */
    fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun markerLines(assetFile: File): List<String>? {
        val marker = markerFor(assetFile)
        if (!marker.isFile) return null
        return runCatching { marker.readLines() }.getOrNull()
    }

    private const val BUFFER_SIZE = 64 * 1024
}
