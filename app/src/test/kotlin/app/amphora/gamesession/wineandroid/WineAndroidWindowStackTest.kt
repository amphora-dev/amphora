package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineAndroidWindowStackTest {
    @Test
    fun styleVisibleMatchesWsVisibleBit() {
        assertFalse(WineAndroidWindowStack.isStyleVisible(0))
        assertFalse(WineAndroidWindowStack.isStyleVisible(0x04cf0000)) // overlapped w/o WS_VISIBLE
        assertTrue(WineAndroidWindowStack.isStyleVisible(WineAndroidWindowStack.WS_VISIBLE))
        assertTrue(WineAndroidWindowStack.isStyleVisible(0x04cf0000 or WineAndroidWindowStack.WS_VISIBLE))
    }

    @Test
    fun wantsZOrderRespectsSwpNoZOrder() {
        assertTrue(WineAndroidWindowStack.wantsZOrder(0))
        assertFalse(WineAndroidWindowStack.wantsZOrder(WineAndroidWindowStack.SWP_NOZORDER))
        assertTrue(WineAndroidWindowStack.wantsZOrder(0x20)) // other flags only
        assertFalse(WineAndroidWindowStack.wantsZOrder(0x20 or WineAndroidWindowStack.SWP_NOZORDER))
    }

    @Test
    fun hwndTopMovesToFront() {
        val ordered =
            WineAndroidWindowStack.reorder(
                siblings = listOf(0x10, 0x20, 0x30),
                hwnd = 0x30,
                insertAfter = WineAndroidWindowStack.HWND_TOP,
            )
        assertEquals(listOf(0x30, 0x10, 0x20), ordered)
    }

    @Test
    fun hwndBottomMovesToBack() {
        val ordered =
            WineAndroidWindowStack.reorder(
                siblings = listOf(0x10, 0x20, 0x30),
                hwnd = 0x10,
                insertAfter = WineAndroidWindowStack.HWND_BOTTOM,
            )
        assertEquals(listOf(0x20, 0x30, 0x10), ordered)
    }

    @Test
    fun insertAfterSiblingPlacesJustBelow() {
        // Top-first: after 0x10 means immediately under 0x10.
        val ordered =
            WineAndroidWindowStack.reorder(
                siblings = listOf(0x10, 0x20, 0x30),
                hwnd = 0x30,
                insertAfter = 0x10,
            )
        assertEquals(listOf(0x10, 0x30, 0x20), ordered)
    }

    @Test
    fun unknownInsertAfterTreatedAsTop() {
        val ordered =
            WineAndroidWindowStack.reorder(
                siblings = listOf(0x10, 0x20),
                hwnd = 0x99,
                insertAfter = 0xdead,
            )
        assertEquals(listOf(0x99, 0x10, 0x20), ordered)
    }

    @Test
    fun topmostAndNotopmostMatchTop() {
        val base = listOf(0x1, 0x2)
        assertEquals(
            listOf(0x2, 0x1),
            WineAndroidWindowStack.reorder(base, 0x2, WineAndroidWindowStack.HWND_TOPMOST),
        )
        assertEquals(
            listOf(0x2, 0x1),
            WineAndroidWindowStack.reorder(base, 0x2, WineAndroidWindowStack.HWND_NOTOPMOST),
        )
    }

    @Test
    fun syncBringToFrontGoesBottomToTopSkippingInvisible() {
        val order =
            WineAndroidWindowStack.syncBringToFrontOrder(
                siblingsTopFirst = listOf(0xA, 0xB, 0xC, 0xD),
                visible = { it != 0xB && it != 0xD },
            )
        // Bottom→top among visible: C then A (A ends frontmost).
        assertEquals(listOf(0xC, 0xA), order)
    }

    @Test
    fun ensureTrackedAndRemove() {
        assertEquals(listOf(1, 2, 3), WineAndroidWindowStack.ensureTracked(listOf(1, 2), 3))
        assertEquals(listOf(1, 2), WineAndroidWindowStack.ensureTracked(listOf(1, 2), 2))
        assertEquals(listOf(1, 3), WineAndroidWindowStack.remove(listOf(1, 2, 3), 2))
    }
}
