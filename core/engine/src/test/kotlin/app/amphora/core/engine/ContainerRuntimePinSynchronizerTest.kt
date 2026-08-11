package app.amphora.core.engine

import com.winlator.cmod.runtime.container.Container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerRuntimePinSynchronizerTest {
    private val synchronizer = ContainerRuntimePinSynchronizer()

    @Test
    fun noChangesDoNotSave() {
        val container = currentContainer()

        synchronize(container)

        assertEquals(0, container.saveCount)
    }

    @Test
    fun wineShaChangeArmsExistingPrefix() {
        val container = currentContainer()
        AppliedMarks.markWineContent(container, "$WINE_VERSION|sha=old-sha")
        AppliedMarks.markDxwrapper(container, "applied-dxwrapper")

        synchronize(container)

        assertEquals(1, container.saveCount)
        assertEquals("$WINE_VERSION|sha=$WINE_SHA", AppliedMarks.wineContent(container))
        assertTrue(AppliedMarks.prefixNeedsUpdate(container))
        assertEquals("", AppliedMarks.dxwrapperKey(container))
    }

    @Test
    fun wineVersionChangeUpdatesPinAndArmsExistingPrefix() {
        val container =
            currentContainer().apply {
                wineVersion = "proton-old"
                AppliedMarks.markWineContent(this, "$WINE_VERSION|sha=$WINE_SHA")
                AppliedMarks.markDxwrapper(this, "applied-dxwrapper")
            }

        synchronize(container)

        assertEquals(WINE_VERSION, container.wineVersion)
        assertEquals(1, container.saveCount)
        assertTrue(AppliedMarks.prefixNeedsUpdate(container))
        assertEquals("", AppliedMarks.dxwrapperKey(container))
    }

    @Test
    fun newContainerWineContentChangeDoesNotArmPrefix() {
        val container =
            currentContainer(wineContentApplied = false).apply {
                AppliedMarks.markDxwrapper(this, "created-prefix-dxwrapper")
            }

        synchronize(container, newlyCreated = true)

        assertEquals(1, container.saveCount)
        assertEquals("$WINE_VERSION|sha=$WINE_SHA", AppliedMarks.wineContent(container))
        assertFalse(AppliedMarks.prefixNeedsUpdate(container))
        assertEquals("created-prefix-dxwrapper", AppliedMarks.dxwrapperKey(container))
    }

    @Test
    fun runtimeFieldMigrationsKeepIndependentSavesAndAppliedMarks() {
        val container =
            currentContainer().apply {
                box64Version = "box64-old"
                dxWrapper = "dxvk-old;vkd3d-old;dd7to9"
                winComponents = OLD_WINCOMPONENTS
                AppliedMarks.markBox64(this, "applied-box64")
                AppliedMarks.markDxwrapper(this, "applied-dxwrapper")
                AppliedMarks.markWincomponents(this, "applied-wincomponents")
            }

        synchronize(container)

        assertEquals(BOX64_VERSION, container.box64Version)
        assertEquals(DXWRAPPER, container.dxWrapper)
        assertEquals(WINCOMPONENTS, container.winComponents)
        assertEquals(3, container.saveCount)
        assertEquals("", AppliedMarks.box64(container))
        assertEquals("", AppliedMarks.dxwrapperKey(container))
        assertEquals("applied-wincomponents", AppliedMarks.wincomponents(container))
    }

    @Test
    fun trustAugmentInvalidatesExactlyOnce() {
        val container =
            currentContainer(trustAugmentApplied = false).apply {
                AppliedMarks.markDxwrapper(this, "first-applied-dxwrapper")
            }

        synchronize(container)

        assertEquals(1, container.saveCount)
        assertEquals("", AppliedMarks.dxwrapperKey(container))
        assertEquals("1", container.getExtra(DXVK_TRUST_AUGMENT_EXTRA))

        AppliedMarks.markDxwrapper(container, "second-applied-dxwrapper")
        synchronize(container)

        assertEquals(1, container.saveCount)
        assertEquals("second-applied-dxwrapper", AppliedMarks.dxwrapperKey(container))
    }

    private fun currentContainer(
        wineContentApplied: Boolean = true,
        trustAugmentApplied: Boolean = true,
    ): RecordingContainer = RecordingContainer().apply {
        wineVersion = WINE_VERSION
        box64Version = BOX64_VERSION
        dxWrapper = DXWRAPPER
        winComponents = WINCOMPONENTS
        if (wineContentApplied) {
            AppliedMarks.markWineContent(this, "$WINE_VERSION|sha=$WINE_SHA")
        }
        if (trustAugmentApplied) {
            putExtra(DXVK_TRUST_AUGMENT_EXTRA, "1")
        }
    }

    private fun synchronize(container: Container, newlyCreated: Boolean = false) {
        synchronizer.syncRuntimePins(
            container = container,
            wineVersion = WINE_VERSION,
            wineSha256 = WINE_SHA,
            box64Version = BOX64_VERSION,
            dxwrapper = DXWRAPPER,
            wincomponents = WINCOMPONENTS,
            newlyCreated = newlyCreated,
        )
    }

    private class RecordingContainer : Container(1) {
        var saveCount: Int = 0
            private set

        override fun saveData(): Boolean {
            saveCount += 1
            return true
        }
    }

    private companion object {
        const val WINE_VERSION = "proton-11.0-1"
        const val WINE_SHA = "wine-sha"
        const val BOX64_VERSION = "0.4.5-0db8df775-0"
        const val DXWRAPPER = "dxvk-3.0.2-1;vkd3d-2.14.1-1;dd7to9"
        const val WINCOMPONENTS = WindowsComponentPreferences.DEFAULT_SELECTION
        const val OLD_WINCOMPONENTS =
            "direct3d=0,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,dinput8=1,vcrun2010=1"
        const val DXVK_TRUST_AUGMENT_EXTRA = "dxvkTrustAugment"
    }
}
