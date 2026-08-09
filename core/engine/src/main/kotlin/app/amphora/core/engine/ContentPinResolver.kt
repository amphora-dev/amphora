package app.amphora.core.engine

import com.winlator.cmod.runtime.content.ContentProfile
import com.winlator.cmod.runtime.content.ContentsManager

/**
 * Single place to resolve **installed** WCP identity for runtime selection.
 *
 * Layers (do not conflate):
 * - **sha256** — authoritative content identity, verified at download and in
 *   the installed directory by `InstalledContentPin`.
 * - **manifest `version`** — compatibility/display name used by ContentsManager
 *   (`Type-verName-verCode`) to locate that SHA-validated install.
 * - **container fields** — cached copy of the compatibility name for launch (`box64Version`,
 *   `wineVersion`, `dxwrapper`). Must be rewritten when the install moves.
 *
 * This resolver only maps compatibility names to profiles. Installation and
 * derived-state gates separately reject or refresh a mismatched SHA.
 */
object ContentPinResolver {
    /**
     * Strip the type prefix from a ContentsManager entry name.
     * `Box64-0.4.5-0db8df775-0` → `0.4.5-0db8df775-0`.
     */
    fun versionIdentity(entryName: String): String {
        val dash = entryName.indexOf('-')
        return if (dash >= 0) entryName.substring(dash + 1) else entryName
    }

    /** `dxvk-<verName>-<verCode>` style token used in container `dxwrapper`. */
    fun wrapperToken(prefix: String, profile: ContentProfile): String = "$prefix-${profile.verName}-${profile.verCode}"

    fun entryName(profile: ContentProfile): String = ContentsManager.getEntryName(profile)

    /**
     * Prefer [preferredEntryName] when that install exists; otherwise newest
     * installed profile of [type]. Null when nothing is installed.
     */
    fun resolveInstalledProfile(
        contentsManager: ContentsManager,
        type: ContentProfile.ContentType,
        preferredEntryName: String?,
    ): ContentProfile? {
        if (!preferredEntryName.isNullOrBlank()) {
            val preferred = contentsManager.getProfileByEntryName(preferredEntryName)
            if (preferred != null && preferred.isInstalled) return preferred
            // Token may already be the identity after the type dash
            // (`0.4.5-…` or `dxvk-2.7.1-…`).
            val asTyped = contentsManager.getProfileByEntryName("$type-$preferredEntryName")
            if (asTyped != null && asTyped.isInstalled) return asTyped
            val byIdentity =
                findInstalledByIdentity(contentsManager, type, preferredEntryName)
            if (byIdentity != null) return byIdentity
        }
        return pickNewestInstalled(contentsManager, type)
    }

    fun pickNewestInstalled(contentsManager: ContentsManager, type: ContentProfile.ContentType): ContentProfile? {
        val profiles = contentsManager.getProfiles(type) ?: return null
        var best: ContentProfile? = null
        for (profile in profiles) {
            if (!profile.isInstalled) continue
            if (best == null ||
                profile.verCode > best.verCode ||
                (
                    profile.verCode == best.verCode &&
                        profile.verName != null &&
                        best.verName != null &&
                        profile.verName.compareTo(best.verName, ignoreCase = true) > 0
                    )
            ) {
                best = profile
            }
        }
        return best
    }

    /**
     * Match [identity] against installed profiles:
     * - full entry (`Box64-…`)
     * - version identity (`0.4.5-…`)
     * - wrapper token (`dxvk-2.7.1-gplasync-0`)
     */
    fun findInstalledByIdentity(
        contentsManager: ContentsManager,
        type: ContentProfile.ContentType,
        identity: String,
    ): ContentProfile? {
        if (identity.isBlank()) return null
        val profiles = contentsManager.getProfiles(type) ?: return null
        val trimmed = identity.trim()
        val withoutWrapperPrefix =
            when {
                trimmed.startsWith("dxvk-", ignoreCase = true) -> trimmed.substringAfter('-')
                trimmed.startsWith("vkd3d-", ignoreCase = true) -> trimmed.substringAfter('-')
                else -> trimmed
            }
        for (profile in profiles) {
            if (!profile.isInstalled) continue
            val entry = entryName(profile)
            val verId = versionIdentity(entry)
            if (entry.equals(trimmed, ignoreCase = true) ||
                verId.equals(trimmed, ignoreCase = true) ||
                verId.equals(withoutWrapperPrefix, ignoreCase = true) ||
                entry.equals("$type-$withoutWrapperPrefix", ignoreCase = true)
            ) {
                return profile
            }
        }
        return null
    }
}
