package app.amphora.core.content.model

/**
 * A kernel asset that must exist under `filesDir/runtime-assets/<assetPath>`.
 *
 * Ported Winlator code still addresses these files by their historical APK
 * asset paths. Amphora's IO bridge transparently prefers the downloaded copy,
 * allowing the APK to stay slim without rewriting every kernel call site.
 */
data class RuntimeAssetEntry(
    val assetPath: String,
    val sha256: String,
    val remoteUrl: String,
    val size: Long? = null,
)
