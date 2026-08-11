package app.amphora.core.content

import android.util.Log
import java.io.File

/**
 * Dev/test escape hatch for [RuntimeAssetProvisioner]: when
 * `<asset>.local-override` sits next to a runtime-assets file, Amphora trusts
 * that local blob and **does not** re-download to match `content_manifest`.
 *
 * Written by `imagefs/ci/wrapper/push-device.sh` (and similar inject helpers).
 * Delete the marker (or pass `--clear` to the script) to return to remote pins.
 *
 * Marker body is the SHA-256 of the local file (same value as `<asset>.sha256`).
 */
object RuntimeAssetLocalOverride {
    const val SUFFIX = ".local-override"
    private const val TAG = "RuntimeAssetLocalOverride"

    fun markerFile(assetFile: File): File = File(assetFile.absolutePath + SUFFIX)

    /** The pin sidecar this override has to agree with. */
    fun shaMarkerFile(assetFile: File): File = AssetDigest.markerFor(assetFile)

    /**
     * True when [assetFile] exists and a matching `.local-override` + `.sha256`
     * pair pins the on-disk bytes (manifest pin is ignored).
     */
    fun isActive(assetFile: File): Boolean {
        if (!assetFile.isFile || assetFile.length() <= 0L) return false
        val override = markerFile(assetFile)
        if (!override.isFile) return false
        val overrideSha = override.readText().trim().lowercase()
        if (!AssetDigest.HEX.matches(overrideSha)) {
            Log.w(TAG, "Ignoring malformed local-override for ${assetFile.name}")
            return false
        }
        val shaMarker = shaMarkerFile(assetFile)
        if (!shaMarker.isFile) return false
        val pinned = AssetDigest.pinnedSha(assetFile)
        if (pinned != overrideSha) {
            Log.w(
                TAG,
                "local-override SHA mismatch for ${assetFile.name}: " +
                    "override=$overrideSha marker=$pinned",
            )
            return false
        }
        return true
    }

    /** Write/refresh override + sha sidecars for an already-placed [assetFile]. */
    fun write(assetFile: File, sha256: String) {
        val digest = sha256.trim().lowercase()
        require(AssetDigest.HEX.matches(digest)) { "invalid sha256: $sha256" }
        require(assetFile.isFile) { "missing asset: $assetFile" }
        AssetDigest.writePin(assetFile, digest)
        markerFile(assetFile).writeText(digest)
        Log.i(TAG, "Armed local-override for ${assetFile.name} ($digest)")
    }

    fun clear(assetFile: File) {
        markerFile(assetFile).delete()
        Log.i(TAG, "Cleared local-override for ${assetFile.name}")
    }
}
