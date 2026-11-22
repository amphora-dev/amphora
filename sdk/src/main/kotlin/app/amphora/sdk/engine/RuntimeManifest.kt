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
    /** The x86 translator next to Wine's modules; null in runtimes without one. */
    val fex: FexInfo? = null,
    /** Direct3D 8 to 11 on Vulkan; null in runtimes without it. */
    val dxvk: D3dInfo? = null,
    /** Direct3D 12 on Vulkan (vkd3d-proton); null in runtimes without it. */
    val vkd3d: D3dInfo? = null,
    /** The GPU driver; null in runtimes that use the system's. */
    val mesa: MesaInfo? = null,
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

/**
 * A Direct3D implementation as amphora-engine built it, laid out as in
 * Proton: [dir]`/aarch64-windows` holds the DLLs x86_64 programs load
 * (ARM64EC), [dir]`/i386-windows` the 32-bit ones.
 */
@Serializable
public data class D3dInfo(
    val version: String,
    val commit: String,
    /** Relative to the runtime root. */
    val dir: String,
    /** Names without `.dll`. */
    val dlls: List<String>,
)

/**
 * Mesa's Turnip, the Vulkan driver for Adreno. [vulkanDriver] is an Android
 * Vulkan HAL module that Wine's display driver opens instead of the system's
 * driver.
 */
@Serializable
public data class MesaInfo(
    val version: String,
    /** Relative to the runtime root. */
    val vulkanDriver: String,
)

/**
 * FEX as amphora-engine built it: `libarm64ecfex.dll` (x86_64) and
 * `libwow64fex.dll` (32-bit x86) in `lib/wine/aarch64-windows`, their unix
 * libraries, and FEX's defaults in [CONFIG_DIR].
 */
@Serializable
public data class FexInfo(val version: String, val commit: String) {
    public companion object {
        /** Holds `Config.json`, relative to the runtime root. */
        public const val CONFIG_DIR: String = "share/fex-emu"
    }
}
