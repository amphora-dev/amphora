package app.amphora.sdk.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The one fixed combination the SDK runs (12 §2.1): which runtime build, what
 * it contains, and the per-game fixes we maintain. Versioned as a whole; a new
 * recipe id means prefixes are rebuilt rather than migrated (D11).
 *
 * Shipped as an asset (`assets/amphora/recipe.json`), so an app build pins
 * exactly one recipe.
 */
@Serializable
public data class Recipe(
    val format: Int,
    val id: String,
    val runtime: RuntimeArtifact,
    /** What the runtime provides; settings that need anything else are shown as unavailable. */
    val components: Set<Component>,
    /** Layer 3 of the settings resolution (13 §2). */
    val fixes: List<GameFix> = emptyList(),
) {
    public companion object {
        public const val FORMAT: Int = 1
    }
}

/** A runtime build published by amphora-engine. */
@Serializable
public data class RuntimeArtifact(
    val name: String,
    val url: String,
    val sha256: String,
    /** Archive size in bytes. */
    val size: Long,
    /** Files and bytes once extracted, excluding the manifest itself. */
    val files: Int,
    val bytes: Long,
)

/** Parts of the stack a recipe can contain. */
@Serializable
public enum class Component(
    public val label: String,
    /** Milestone that brings it, for "not in this recipe yet" hints. */
    public val milestone: String,
) {
    @SerialName("wine")
    Wine("Wine", "M1"),

    @SerialName("fex")
    Fex("FEX", "M2"),

    @SerialName("dxvk")
    Dxvk("DXVK", "M3"),

    @SerialName("vkd3d")
    Vkd3d("vkd3d-proton", "M3"),

    @SerialName("mesa")
    Mesa("Mesa（Turnip / Zink）", "M3"),

    @SerialName("audio")
    Audio("音频", "M3"),
}

/** A per-game setting fix we maintain (13 §2, layer 3). */
@Serializable
public data class GameFix(
    val match: FixMatch,
    /** Setting id → value. */
    val values: Map<String, String>,
    /** Shown to the user when the fix overrides their global setting. */
    val reason: String,
    /** Where the fix comes from: a Proton table, an issue, a test run. */
    val source: String,
)

/** Matches when any given field matches. */
@Serializable
public data class FixMatch(val exeSha256: String? = null, val exeName: String? = null, val steamAppId: Long? = null)
