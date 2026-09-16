package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineAndroidCursorSpecTest {
    @Test
    fun nullHandleHidesViaTypeNull() {
        val spec = WineAndroidCursorSpec.classify(0, 0, 0, 0, 0, null)
        assertEquals(WineAndroidCursorSpec.System(0), spec)
    }

    @Test
    fun emptyBitsUsesSystemId() {
        val spec = WineAndroidCursorSpec.classify(1000, 0, 0, 0, 0, IntArray(0))
        assertEquals(WineAndroidCursorSpec.System(1000), spec)
    }

    @Test
    fun customBitsWhenSized() {
        val bits = intArrayOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(), 0)
        val spec = WineAndroidCursorSpec.classify(0, 2, 2, 1, 0, bits)
        assertTrue(spec is WineAndroidCursorSpec.Custom)
        val custom = spec as WineAndroidCursorSpec.Custom
        assertEquals(2, custom.width)
        assertEquals(2, custom.height)
        assertEquals(1, custom.hotspotX)
        assertEquals(0, custom.hotspotY)
        assertEquals(4, custom.bits.size)
    }

    @Test
    fun shortBitsFallBackToSystem() {
        val spec = WineAndroidCursorSpec.classify(1008, 2, 2, 0, 0, intArrayOf(1))
        assertEquals(WineAndroidCursorSpec.System(1008), spec)
    }

    @Test
    fun hotspotClampedIntoBitmap() {
        val bits = IntArray(4) { 0xFFFFFFFF.toInt() }
        val spec = WineAndroidCursorSpec.classify(0, 2, 2, 99, -3, bits) as WineAndroidCursorSpec.Custom
        assertEquals(1, spec.hotspotX)
        assertEquals(0, spec.hotspotY)
    }
}
