package app.amphora.core.content

import app.amphora.core.content.model.ComponentId
import app.amphora.core.content.model.ContentArtifact

/**
 * Pluggable content source (RFC §4 / §6). Production ships [RemoteContentSource],
 * which downloads SHA-pinned .wcp-style packages on demand; the engine is
 * agnostic to which implementation is bound.
 *
 * ```
 * feature -> engine -> {native, rootfs, content, container}   (RFC §6, strict)
 * ```
 */
interface ContentSource {
    suspend fun resolve(component: ComponentId): ContentArtifact
}
