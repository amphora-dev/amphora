package app.amphora.gamesession.wineandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Locks opcode numbers + payload sizes to upstream device.c
 * (amphora/wineandroid-backport tip 911b761c370).
 */
class WineAndroidProtocolTest {
    @Test
    fun ioctlOpcodeOrderMatchesUpstreamDeviceC() {
        assertEquals(0, WineAndroidProtocol.IOCTL_CREATE_DESKTOP_VIEW)
        assertEquals(1, WineAndroidProtocol.IOCTL_CREATE_WINDOW)
        assertEquals(2, WineAndroidProtocol.IOCTL_DESTROY_WINDOW)
        assertEquals(3, WineAndroidProtocol.IOCTL_WINDOW_POS_CHANGED)
        assertEquals(4, WineAndroidProtocol.IOCTL_SET_WINDOW_PARENT)
        assertEquals(5, WineAndroidProtocol.IOCTL_DEQUEUE_BUFFER)
        assertEquals(6, WineAndroidProtocol.IOCTL_QUEUE_BUFFER)
        assertEquals(7, WineAndroidProtocol.IOCTL_CANCEL_BUFFER)
        assertEquals(8, WineAndroidProtocol.IOCTL_QUERY)
        assertEquals(9, WineAndroidProtocol.IOCTL_PERFORM)
        assertEquals(10, WineAndroidProtocol.IOCTL_SET_SWAP_INT)
        assertEquals(11, WineAndroidProtocol.IOCTL_SET_CAPTURE)
        assertEquals(12, WineAndroidProtocol.IOCTL_SET_CURSOR)
        assertEquals(13, WineAndroidProtocol.NB_IOCTLS)
    }

    @Test
    fun payloadSizesMatchUpstreamStructs() {
        assertEquals(8, WineAndroidProtocol.SIZE_HEADER)
        assertEquals(12, WineAndroidProtocol.SIZE_CREATE_DESKTOP_VIEW)
        assertEquals(16, WineAndroidProtocol.SIZE_CREATE_WINDOW)
        assertEquals(8, WineAndroidProtocol.SIZE_DESTROY_WINDOW)
        assertEquals(72, WineAndroidProtocol.SIZE_WINDOW_POS_CHANGED)
        assertEquals(12, WineAndroidProtocol.SIZE_SET_WINDOW_PARENT)
        assertEquals(16, WineAndroidProtocol.SIZE_DEQUEUE_BUFFER)
        assertEquals(28, WineAndroidProtocol.SIZE_PERFORM)
        assertEquals(64, WineAndroidProtocol.EVENT_DATA_SIZE)
    }

    @Test
    fun eventTypeOrderMatchesUpstreamAndroidH() {
        assertEquals(0, WineAndroidProtocol.EVENT_DESKTOP_CHANGED)
        assertEquals(1, WineAndroidProtocol.EVENT_CONFIG_CHANGED)
        assertEquals(2, WineAndroidProtocol.EVENT_SURFACE_CHANGED)
        assertEquals(3, WineAndroidProtocol.EVENT_MOTION)
        assertEquals(4, WineAndroidProtocol.EVENT_KEYBOARD)
    }

    @Test
    fun abstractNameIsUpstreamWineAndroidDevice() {
        // Leading NUL + \Device\WineAndroid (20 bytes on the wire).
        assertEquals(0, WineAndroidProtocol.ABSTRACT_NAME[0].code)
        assertEquals("\\Device\\WineAndroid", WineAndroidProtocol.ABSTRACT_NAME.substring(1))
        assertEquals(20, WineAndroidProtocol.ABSTRACT_NAME.toByteArray(Charsets.ISO_8859_1).size)
    }

    @Test
    fun keyboardEventWireMatchesWin64KbdLayout() {
        assertEquals(0, WineAndroidProtocol.INPUT_MOUSE)
        assertEquals(1, WineAndroidProtocol.INPUT_KEYBOARD)
        assertEquals(0x0001, WineAndroidProtocol.KEYEVENTF_EXTENDEDKEY)
        assertEquals(0x0002, WineAndroidProtocol.KEYEVENTF_KEYUP)
        assertEquals(0, WineAndroidProtocol.KBD_OFF_TYPE)
        assertEquals(8, WineAndroidProtocol.KBD_OFF_HWND)
        assertEquals(16, WineAndroidProtocol.KBD_OFF_LOCK_STATE)
        assertEquals(24, WineAndroidProtocol.KBD_OFF_INPUT_TYPE)
        assertEquals(32, WineAndroidProtocol.KBD_OFF_WVK)
        assertEquals(34, WineAndroidProtocol.KBD_OFF_WSCAN)
        assertEquals(36, WineAndroidProtocol.KBD_OFF_DWFLAGS)
        assertEquals(40, WineAndroidProtocol.KBD_OFF_TIME)
        assertEquals(48, WineAndroidProtocol.KBD_OFF_EXTRA)
        assertEquals(64, WineAndroidProtocol.EVENT_DATA_SIZE)
    }

    @Test
    fun privateHostOpcodesAreGone() {
        // Reflective guard: no HOST_SURFACE_CHANGED / HOST_DESKTOP_CHANGED constants.
        val names = WineAndroidProtocol::class.java.fields.map { it.name }.toSet()
        assertFalse(names.contains("HOST_SURFACE_CHANGED"))
        assertFalse(names.contains("HOST_DESKTOP_CHANGED"))
        assertFalse(names.any { it.startsWith("HOST_") })
    }
}
