package app.amphora.core.content.model

import java.io.File

sealed interface ContentArtifact {
    val component: ContentComponent

    /** Bundled inside the APK assets; resolved to the filesystem on first run. */
    data class Bundled(
        override val component: ContentComponent,
        val assetPath: String,
        val sha256: String,
    ) : ContentArtifact

    /** Already extracted onto the filesystem and ready to use. */
    data class Resolved(
        override val component: ContentComponent,
        val path: File,
        val version: String,
    ) : ContentArtifact
}
