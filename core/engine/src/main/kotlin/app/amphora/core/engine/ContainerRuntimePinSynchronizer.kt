package app.amphora.core.engine

import com.winlator.cmod.runtime.container.Container

/**
 * Keeps a container's cached runtime pins aligned with the resolved content set.
 *
 * Each migration deliberately persists independently. Besides preserving the
 * existing write behavior, this ensures every invalidated applied mark is
 * durable before the next migration starts.
 */
internal class ContainerRuntimePinSynchronizer {
    fun syncRuntimePins(
        container: Container,
        wineVersion: String,
        wineSha256: String,
        box64Version: String,
        dxwrapper: String,
        wincomponents: String,
        newlyCreated: Boolean,
    ) {
        ensurePinnedWineVersion(container, wineVersion, wineSha256, newlyCreated)
        ensurePinnedBox64Version(container, box64Version)
        ensureRealDxwrapper(container, dxwrapper)
        ensureWinComponents(container, wincomponents)
        // One-shot: incomplete fork DXVK profile.json omitted on-disk d3d8/d3d10*;
        // clear the preparer gate so applyContent re-runs with trust augment.
        // (Self-built 3.0.2 does not ship d3d10/d3d10_1; those are Wine front-ends.)
        ensureDxvkTrustAugmentReapply(container)
    }

    /**
     * Rewrite [container]'s `wineVersion` when the manifest WINE pin moves.
     *
     * The prefix belongs to the Proton it was unpacked from, so this also arms
     * `wineprefixNeedsUpdate` (`repairContainerWinePrefix` carries saves) and
     * clears the dxwrapper gate so DXVK/VKD3D DLLs land in the fresh prefix.
     */
    private fun ensurePinnedWineVersion(container: Container, desired: String, sha256: String, newlyCreated: Boolean) {
        val current = container.getWineVersion() ?: ""
        val desiredContent = "$desired|sha=$sha256"
        val versionChanged = current != desired
        val contentChanged = AppliedMarks.needsWineContent(container, desiredContent)
        if (!versionChanged && !contentChanged) return

        android.util.Log.i(
            TAG,
            "Refreshing container Wine '$current' -> '$desired' (contentChanged=$contentChanged)",
        )
        if (versionChanged) container.setWineVersion(desired)
        AppliedMarks.markWineContent(container, desiredContent)
        if (!newlyCreated) {
            AppliedMarks.markPrefixNeedsUpdate(container)
            AppliedMarks.invalidateDxwrapper(container)
        }
        container.saveData()
    }

    /**
     * Rewrite [container]'s `box64Version` when reconcile pruned the old install.
     * Clears the applied mark so `usr/bin/box64` is re-installed.
     */
    private fun ensurePinnedBox64Version(container: Container, desired: String) {
        if (desired.isEmpty()) return
        val current = container.getBox64Version() ?: ""
        if (current == desired) return

        android.util.Log.i(
            TAG,
            "Migrating container box64Version '$current' -> '$desired'",
        )
        container.setBox64Version(desired)
        AppliedMarks.invalidateBox64(container)
        container.saveData()
    }

    /**
     * Rewrite [container]'s `dxwrapper` when it differs from the manifest-pinned
     * [desired] token (legacy `dxvk-1.0` / `vkd3d-None` / version bumps). Clears
     * the applied mark so DLLs are re-applied on next launch.
     */
    private fun ensureRealDxwrapper(container: Container, desired: String) {
        val current = container.getDXWrapper() ?: ""
        if (current == desired) return

        android.util.Log.i(
            TAG,
            "Migrating container dxwrapper '$current' -> '$desired'",
        )
        container.setDXWrapper(desired)
        AppliedMarks.invalidateDxwrapper(container)
        container.saveData()
    }

    private fun ensureWinComponents(container: Container, desired: String) {
        val current = WindowsComponentPreferences.normalize(container.getWinComponents() ?: "")
        if (current == desired) return
        android.util.Log.i(
            TAG,
            "Migrating container wincomponents '$current' -> '$desired'",
        )
        container.setWinComponents(desired)
        container.saveData()
    }

    /**
     * Force one DXVK re-apply after ContentsManager started augmenting
     * trust-listed DLLs missing from incomplete fork `profile.json`
     * (notably `d3d8.dll` on Dxvk-2.7.1-gplasync; that fork also shipped
     * `d3d10.dll` / `d3d10_1.dll`, which Amphora's 3.0.2 WCP does not).
     */
    private fun ensureDxvkTrustAugmentReapply(container: Container) {
        if (container.getExtra(DXVK_TRUST_AUGMENT_EXTRA) == "1") return
        android.util.Log.i(
            TAG,
            "Clearing dxwrapper applied mark for DXVK trust-file augment re-apply",
        )
        AppliedMarks.invalidateDxwrapper(container)
        container.putExtra(DXVK_TRUST_AUGMENT_EXTRA, "1")
        container.saveData()
    }

    private companion object {
        private const val TAG = "WinlatorContainerManager"

        /** Marks that the DXVK trust-file augment re-apply has been scheduled once. */
        private const val DXVK_TRUST_AUGMENT_EXTRA = "dxvkTrustAugment"
    }
}
