package app.amphora.gamesession.wineandroid

/**
 * Upstream wineandroid wire protocol constants (dlls/wineandroid.drv/device.c).
 *
 * Transport: abstract AF_UNIX SOCK_SEQPACKET `\0\Device\WineAndroid`.
 * Request message: `[int32 code][payload…]`
 * Reply message:   `[int32 status][payload…]` + optional SCM_RIGHTS.
 *
 * Opcode values and payload layouts must stay byte-identical to the proton
 * branch `amphora/wineandroid-backport` (tip 911b761c370). Do **not** add
 * Amphora-only HOST_* opcodes (former 100/101 are deleted).
 */
object WineAndroidProtocol {
    const val ABSTRACT_NAME = "\u0000\\Device\\WineAndroid"

    /** device.c `enum android_ioctl` — full set. */
    const val IOCTL_CREATE_DESKTOP_VIEW = 0
    const val IOCTL_CREATE_WINDOW = 1
    const val IOCTL_DESTROY_WINDOW = 2
    const val IOCTL_WINDOW_POS_CHANGED = 3
    const val IOCTL_SET_WINDOW_PARENT = 4
    const val IOCTL_DEQUEUE_BUFFER = 5
    const val IOCTL_QUEUE_BUFFER = 6
    const val IOCTL_CANCEL_BUFFER = 7
    const val IOCTL_QUERY = 8
    const val IOCTL_PERFORM = 9
    const val IOCTL_SET_SWAP_INT = 10
    const val IOCTL_SET_CAPTURE = 11
    const val IOCTL_SET_CURSOR = 12

    /** Tip AMPHORA_BUF wine FD via SCM_RIGHTS (host_anw socketpair). */
    const val IOCTL_GET_BUFFER_SOCK = 13
    const val NB_IOCTLS = 14

    /** ioctl_header { int hwnd; BOOL opengl; } */
    const val SIZE_HEADER = 8

    const val SIZE_CREATE_DESKTOP_VIEW = SIZE_HEADER + 4 // log_flags
    const val SIZE_CREATE_WINDOW = SIZE_HEADER + 4 + 4 // parent, is_desktop
    const val SIZE_DESTROY_WINDOW = SIZE_HEADER
    const val SIZE_WINDOW_POS_CHANGED = SIZE_HEADER + (4 * 4 * 3) + (4 * 4)
    const val SIZE_SET_WINDOW_PARENT = SIZE_HEADER + 4
    const val SIZE_DEQUEUE_BUFFER = SIZE_HEADER + 8
    const val SIZE_QUEUE_BUFFER = SIZE_HEADER + 8
    const val SIZE_CANCEL_BUFFER = SIZE_HEADER + 8
    const val SIZE_QUERY = SIZE_HEADER + 8
    const val SIZE_PERFORM = SIZE_HEADER + 4 + 16
    const val SIZE_SET_SWAP_INT = SIZE_HEADER + 4
    const val SIZE_SET_CAPTURE = SIZE_HEADER
    const val SIZE_GET_BUFFER_SOCK = SIZE_HEADER

    /** Win64 `union event_data` wire size written to the desktop event pipe. */
    const val EVENT_DATA_SIZE = 64

    const val EVENT_DESKTOP_CHANGED = 0
    const val EVENT_CONFIG_CHANGED = 1
    const val EVENT_SURFACE_CHANGED = 2
    const val EVENT_MOTION = 3
    const val EVENT_KEYBOARD = 4

    /** Win64 INPUT.type (winuser.h). */
    const val INPUT_MOUSE = 0
    const val INPUT_KEYBOARD = 1

    /** Win64 KEYBDINPUT.dwFlags. */
    const val KEYEVENTF_EXTENDEDKEY = 0x0001
    const val KEYEVENTF_KEYUP = 0x0002

    /**
     * Win64 `union event_data.kbd` wire offsets (EVENT_DATA_SIZE = 64).
     * type@0, hwnd@8, lock_state@16, INPUT.type@24, ki.wVk@32, ki.wScan@34,
     * dwFlags@36, time@40, dwExtraInfo@48.
     */
    const val KBD_OFF_TYPE = 0
    const val KBD_OFF_HWND = 8
    const val KBD_OFF_LOCK_STATE = 16
    const val KBD_OFF_INPUT_TYPE = 24
    const val KBD_OFF_WVK = 32
    const val KBD_OFF_WSCAN = 34
    const val KBD_OFF_DWFLAGS = 36
    const val KBD_OFF_TIME = 40
    const val KBD_OFF_EXTRA = 48
}
