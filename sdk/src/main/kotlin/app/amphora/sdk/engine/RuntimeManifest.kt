package app.amphora.sdk.engine

import kotlinx.serialization.Serializable

/**
 * `amphora-runtime.json` at the root of a runtime archive, written by
 * amphora-engine. The installer checks it against the [RuntimeArtifact] that
 * was asked for before the runtime is made visible.
 */
@Serializable
public data class RuntimeManifest(
    val format: Int,
    val name: String,
    val abi: String,
    val minSdk: Int,
    val wine: WineInfo,
    /** Paths relative to the runtime root. */
    val loader: String,
    val server: String,
    val libraryPath: List<String>,
    /** Files and bytes in the archive, excluding this manifest. */
    val files: Int,
    val bytes: Long,
) {
    public companion object {
        public const val FILE_NAME: String = "amphora-runtime.json"
        public const val FORMAT: Int = 1
    }
}

@Serializable
public data class WineInfo(val version: String, val commit: String, val upstreamTag: String, val archs: List<String>)
