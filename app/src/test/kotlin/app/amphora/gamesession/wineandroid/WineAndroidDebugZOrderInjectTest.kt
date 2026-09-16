package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WineAndroidDebugZOrderInjectTest {
    @Test
    fun extraConstantsMatchSmokeContract() {
        assertEquals(
            "app.amphora.debug.DUMP_ZORDER",
            WineAndroidDebugZOrderInject.EXTRA_DUMP_ZORDER,
        )
        assertEquals(
            "app.amphora.debug.ZORDER_TOP_HWND",
            WineAndroidDebugZOrderInject.EXTRA_ZORDER_TOP_HWND,
        )
    }

    @Test
    fun dumpNullWhenNotDebuggableOrAbsent() {
        assertNull(
            WineAndroidDebugZOrderInject.dumpRequestedIfDebuggable(
                present = true,
                value = true,
                debuggable = false,
            ),
        )
        assertNull(
            WineAndroidDebugZOrderInject.dumpRequestedIfDebuggable(
                present = false,
                value = true,
                debuggable = true,
            ),
        )
    }

    @Test
    fun dumpReturnsValueWhenPresentAndDebuggable() {
        assertEquals(
            true,
            WineAndroidDebugZOrderInject.dumpRequestedIfDebuggable(
                present = true,
                value = true,
                debuggable = true,
            ),
        )
        assertEquals(
            false,
            WineAndroidDebugZOrderInject.dumpRequestedIfDebuggable(
                present = true,
                value = false,
                debuggable = true,
            ),
        )
    }

    @Test
    fun zOrderTopNullWhenNotDebuggableOrAbsent() {
        assertNull(
            WineAndroidDebugZOrderInject.zOrderTopHwndIfDebuggable(
                present = true,
                value = 0x42,
                debuggable = false,
            ),
        )
        assertNull(
            WineAndroidDebugZOrderInject.zOrderTopHwndIfDebuggable(
                present = false,
                value = 0x42,
                debuggable = true,
            ),
        )
    }

    @Test
    fun zOrderTopReturnsValueIncludingZero() {
        assertEquals(
            0x10,
            WineAndroidDebugZOrderInject.zOrderTopHwndIfDebuggable(
                present = true,
                value = 0x10,
                debuggable = true,
            ),
        )
        assertEquals(
            0,
            WineAndroidDebugZOrderInject.zOrderTopHwndIfDebuggable(
                present = true,
                value = 0,
                debuggable = true,
            ),
        )
    }

    @Test
    fun relayOmitsOrPuts() {
        assertNull(WineAndroidDebugZOrderInject.relayDump(dumpPresent = false, dumpValue = true))
        assertEquals(
            true,
            WineAndroidDebugZOrderInject.relayDump(dumpPresent = true, dumpValue = true),
        )
        assertEquals(
            false,
            WineAndroidDebugZOrderInject.relayDump(dumpPresent = true, dumpValue = false),
        )
        assertNull(
            WineAndroidDebugZOrderInject.relayZOrderTop(topPresent = false, topValue = 1),
        )
        assertEquals(
            0x55,
            WineAndroidDebugZOrderInject.relayZOrderTop(topPresent = true, topValue = 0x55),
        )
    }

    @Test
    fun formatStackLineMarksVisible() {
        val line =
            WineAndroidDebugZOrderInject.formatStackLine(
                parentKey = 0,
                topFirst = listOf(0x10, 0x20, 0x30),
                visible = { it == 0x10 || it == 0x30 },
            )
        assertEquals(
            "zorder dump parentKey=0 topFirst=[0x10*,0x20,0x30*]",
            line,
        )
    }

    @Test
    fun formatStackLineEmpty() {
        assertEquals(
            "zorder dump parentKey=7 topFirst=[(empty)]",
            WineAndroidDebugZOrderInject.formatStackLine(
                parentKey = 7,
                topFirst = emptyList(),
                visible = { false },
            ),
        )
    }

    @Test
    fun hasOverlapCandidatesRequiresTwoVisible() {
        assertFalse(
            WineAndroidDebugZOrderInject.hasOverlapCandidates(
                topFirst = listOf(0x1, 0x2),
                visible = { it == 0x1 },
            ),
        )
        assertTrue(
            WineAndroidDebugZOrderInject.hasOverlapCandidates(
                topFirst = listOf(0x1, 0x2, 0x3),
                visible = { it != 0x2 },
            ),
        )
    }

    @Test
    fun formatSyncLine() {
        assertEquals(
            "zorder sync reason=apply parentKey=0 topFirst=[0x30,0x10] bring=[0x10,0x30]",
            WineAndroidDebugZOrderInject.formatSyncLine(
                parentKey = 0,
                topFirst = listOf(0x30, 0x10),
                bringOrder = listOf(0x10, 0x30),
                reason = "apply",
            ),
        )
    }
}
