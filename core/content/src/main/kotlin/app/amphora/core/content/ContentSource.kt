package app.amphora.core.content

import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentArtifact

/**
 * Pluggable content source (RFC §4 / §6). MVP ships a [BundledContentSource] that
 * serves version-locked artifacts from APK assets; later a [RemoteContentSource]
 * downloads .wcp-style packages. The engine is agnostic to which is active.
 *
 * ```
 * feature -> engine -> {native, rootfs, content, container}   (RFC §6, strict)
 * ```
 */
interface ContentSource {
    suspend fun resolve(component: ComponentId): ContentArtifact
}
