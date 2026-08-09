package app.amphora.core.content

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * SHA identity recorded inside an extracted/installed content directory.
 *
 * Package versions remain useful display and compatibility metadata, but the
 * manifest SHA is the authoritative installation identity.
 */
object InstalledContentPin {
    const val MARKER_NAME = ".amphora-source.sha256"

    fun read(installDir: File): String? =
        File(installDir, MARKER_NAME)
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.lowercase()
            ?.takeIf(AssetDigest.HEX::matches)

    fun matches(installDir: File, expectedSha256: String?): Boolean {
        val expected = expectedSha256?.trim()?.lowercase()?.takeIf(AssetDigest.HEX::matches) ?: return false
        return installDir.isDirectory && read(installDir) == expected
    }

    fun write(installDir: File, sha256: String) {
        val digest = sha256.trim().lowercase()
        require(AssetDigest.HEX.matches(digest)) { "invalid sha256: $sha256" }
        check(installDir.isDirectory) { "install directory is missing: $installDir" }
        writeAtomically(File(installDir, MARKER_NAME), digest)
    }

    private fun writeAtomically(marker: File, value: String) {
        marker.parentFile?.mkdirs()
        val partial = File(marker.absolutePath + ".part")
        partial.writeText(value)
        if (marker.exists()) check(marker.delete()) { "cannot replace content pin: $marker" }
        check(partial.renameTo(marker)) { "cannot publish content pin: $partial -> $marker" }
    }
}

/**
 * Tracks a runtime asset after it has been copied or extracted somewhere else.
 *
 * The source file's [AssetDigest] sidecar is compared with a namespaced marker
 * below the target root. This prevents a verified new download from leaving an
 * old derived payload in imagefs, a Wine prefix, or a driver directory.
 */
object AppliedAssetPin {
    private const val MARKER_DIRECTORY = ".amphora-applied"

    fun sourceSha(sourceAsset: File): String? = AssetDigest.pinnedSha(sourceAsset)

    fun needsApply(targetRoot: File, sourceAsset: File, relativeAssetPath: String): Boolean {
        val expected = sourceSha(sourceAsset) ?: return true
        return read(targetRoot, relativeAssetPath) != expected
    }

    fun markApplied(targetRoot: File, sourceAsset: File, relativeAssetPath: String) {
        val sha = requireNotNull(sourceSha(sourceAsset)) {
            "verified source pin is missing: $sourceAsset"
        }
        val marker = markerFor(targetRoot, relativeAssetPath)
        marker.parentFile?.mkdirs()
        marker.writeText(sha)
    }

    fun read(targetRoot: File, relativeAssetPath: String): String? =
        markerFor(targetRoot, relativeAssetPath)
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.lowercase()
            ?.takeIf(AssetDigest.HEX::matches)

    /**
     * Stable compact key for configuration gates whose output depends on one or
     * more runtime assets. Missing pins intentionally participate in the key.
     */
    fun fingerprint(runtimeAssetsRoot: File, relativeAssetPaths: Iterable<String>): String {
        val state =
            relativeAssetPaths
                .map(::normalize)
                .distinct()
                .sorted()
                .joinToString("\n") { path ->
                    "$path=${AssetDigest.pinnedSha(File(runtimeAssetsRoot, path)) ?: "missing"}"
                }
        return ByteArrayInputStream(state.toByteArray(StandardCharsets.UTF_8)).use { AssetDigest.of(it) }
    }

    private fun markerFor(targetRoot: File, relativeAssetPath: String): File =
        File(File(targetRoot, MARKER_DIRECTORY), normalize(relativeAssetPath) + AssetDigest.SHA_SUFFIX)

    private fun normalize(relativeAssetPath: String): String {
        val normalized = relativeAssetPath.replace('\\', '/').trim('/')
        require(
            normalized.isNotEmpty() &&
                normalized.split('/').none { it.isEmpty() || it == "." || it == ".." },
        ) {
            "invalid relative asset path: $relativeAssetPath"
        }
        return normalized
    }
}
