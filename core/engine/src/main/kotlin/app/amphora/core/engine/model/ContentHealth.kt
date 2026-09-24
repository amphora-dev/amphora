package app.amphora.core.engine.model

import app.amphora.core.content.model.ContentComponent

/** A point-in-time view of manifest pins and their corresponding local artifacts. */
data class ContentHealthSnapshot(
    val components: List<ContentComponentHealth>,
    val runtimeAssets: List<RuntimeAssetHealth>,
    val imageFsResidue: Boolean,
)

/** Installation state for one manifest component. */
data class ContentComponentHealth(
    val component: ContentComponent,
    val pinned: String?,
    val installed: String?,
    val state: State,
) {
    enum class State {
        READY,

        /** Installed at the effective catalog pin which came from [app.amphora.core.content.DevPinOverlay]. */
        LOCAL_OVERRIDE,
        MISSING,
        UPDATE,
        NO_PIN,
    }

    val healthy: Boolean get() = state == State.READY || state == State.LOCAL_OVERRIDE
}

/** SHA and installation state for one manifest `runtimeAssets[]` entry. */
data class RuntimeAssetHealth(
    val assetPath: String,
    val pinnedSha: String,
    val installedSha: String?,
    val sizeBytes: Long?,
    val state: State,
) {
    enum class State {
        READY,
        MISSING,
        MISMATCH,
        UNVERIFIED,

        /** Matches the effective pin from [app.amphora.core.content.DevPinOverlay]. */
        LOCAL_OVERRIDE,
    }

    val healthy: Boolean get() = state == State.READY || state == State.LOCAL_OVERRIDE
}
