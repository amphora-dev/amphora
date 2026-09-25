package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-resolution letterbox cases for docs/04 hostScale TODO item 1
 * (math / no-crop / centered) without needing a second physical device.
 */
class WineAndroidHostScaleTest {
    @Test
    fun ha262LandscapeMatchesKnownScale() {
        // Y700 panel used by Present smokes: 3040×1904 into 1280×720 guest.
        val layout = WineAndroidHostScale.compute(1280, 720, 3040, 1904)
        assertEquals(2.375f, layout.scale, 0.001f)
        assertEquals(3040, layout.contentWidth)
        assertEquals(1710, layout.contentHeight)
        assertEquals(0, layout.offsetX)
        assertEquals(97, layout.offsetY)
    }

    @Test
    fun portraitPhoneLetterboxesHorizontally() {
        val layout = WineAndroidHostScale.compute(1280, 720, 1080, 2400)
        assertEquals(0.84375f, layout.scale, 0.0001f)
        assertEquals(1080, layout.contentWidth)
        assertEquals(607, layout.contentHeight)
        assertEquals(0, layout.offsetX)
        assertEquals((2400 - 607) / 2, layout.offsetY)
    }

    @Test
    fun ultrawideLetterboxesVertically() {
        val layout = WineAndroidHostScale.compute(1280, 720, 3840, 1600)
        assertEquals(1600f / 720f, layout.scale, 0.0001f)
        assertEquals(1600, layout.contentHeight)
        assertTrue(layout.offsetX > 0)
        assertEquals(0, layout.offsetY)
    }

    @Test
    fun squareHostCentersOnHeightWhenWidthLimited() {
        // min(2000/1280, 2000/720) = 2000/1280 → fill width, letterbox top/bottom.
        val layout = WineAndroidHostScale.compute(1280, 720, 2000, 2000)
        assertEquals(2000f / 1280f, layout.scale, 0.0001f)
        assertEquals(0, layout.offsetX)
        assertEquals(2000, layout.contentWidth)
        assertTrue(layout.offsetY > 0)
    }

    @Test
    fun invalidSizesYieldIdentity() {
        assertEquals(WineAndroidHostScaleLayout.IDENTITY, WineAndroidHostScale.compute(0, 720, 100, 100))
        assertEquals(WineAndroidHostScaleLayout.IDENTITY, WineAndroidHostScale.compute(1280, 720, -1, 100))
    }

    @Test
    fun contentNeverExceedsHost() {
        val cases =
            listOf(
                intArrayOf(1280, 720, 3040, 1904),
                intArrayOf(1280, 720, 1080, 2400),
                intArrayOf(1280, 720, 2560, 1600),
                intArrayOf(1920, 1080, 1280, 800),
            )
        for (c in cases) {
            val layout = WineAndroidHostScale.compute(c[0], c[1], c[2], c[3])
            assertTrue(layout.contentWidth <= c[2])
            assertTrue(layout.contentHeight <= c[3])
            assertTrue(layout.offsetX >= 0)
            assertTrue(layout.offsetY >= 0)
            assertTrue(layout.offsetX + layout.contentWidth <= c[2])
            assertTrue(layout.offsetY + layout.contentHeight <= c[3])
        }
    }
}
