package app.amphora.gamesession.wineandroid

import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stream framing + payloads for the Amphora wineandroid host socket.
 *
 * **Do not invent ioctl field layouts.** Window opcodes and payload field order
 * match `dlls/wineandroid.drv/device.c` (`enum android_ioctl` +
 * `struct ioctl_android_*`). Host dispatch then maps into WineActivity-shaped
 * methods on [WineAndroidHostBridge].
 *
 * Stream frame (little-endian), SOCK_STREAM:
 * ```
 * int32 opcode   // android_ioctl value, or Amphora host→unix extras below
 * int32 nbytes   // payload length
 * u8[nbytes]     // packed fields
 * ```
 *
 * Wine sizes: `int`/`BOOL`/`LONG` = int32, `float` = float32, `RECT` =
 * `{ LONG left, top, right, bottom }` (device.c / windef.h).
 */
object WineAndroidProtocol {
    const val BYTE_ORDER_NAME = "LITTLE_ENDIAN"
    private val ORDER = ByteOrder.LITTLE_ENDIAN

    /** device.c `enum android_ioctl` — window subset used by the host. */
    const val IOCTL_CREATE_WINDOW = 0
    const val IOCTL_DESTROY_WINDOW = 1
    const val IOCTL_WINDOW_POS_CHANGED = 2
    const val IOCTL_SET_WINDOW_PARENT = 3
    // 4..11 = buffer/query/cursor — stay on unix once ANativeWindow is registered

    /**
     * Amphora host→unix (not an ioctl). Documents the future
     * `wine_surface_changed(hwnd, surface, opengl)` replacement.
     * Payload today: hwnd, opengl, ready (1=Surface available). Native handle /
     * fd for `register_native_window` is **not** sent this turn.
     */
    const val HOST_SURFACE_CHANGED = 100

    /** ioctl_header { int hwnd; BOOL opengl; } */
    const val SIZE_HEADER = 8

    /** ioctl_android_create_window { hdr; int parent; float scale; } + int pid (WineActivity). */
    const val SIZE_CREATE_WINDOW = SIZE_HEADER + 4 + 4 + 4

    /** ioctl_android_destroy_window { hdr } */
    const val SIZE_DESTROY_WINDOW = SIZE_HEADER

    /** ioctl_android_window_pos_changed { hdr; RECT×3; style; flags; after; owner } */
    const val SIZE_WINDOW_POS_CHANGED = SIZE_HEADER + (4 * 4 * 3) + (4 * 4)

    /** ioctl_android_set_window_parent { hdr; int parent; float scale; } + int pid */
    const val SIZE_SET_WINDOW_PARENT = SIZE_HEADER + 4 + 4 + 4

    /** HOST_SURFACE_CHANGED { int hwnd; BOOL opengl; int ready } */
    const val SIZE_SURFACE_CHANGED = 12

    sealed class HostMessage {
        data class CreateWindow(
            val hwnd: Int,
            val opengl: Boolean,
            val parent: Int,
            val scale: Float,
            val pid: Int,
        ) : HostMessage()

        data class DestroyWindow(val hwnd: Int) : HostMessage()

        data class WindowPosChanged(
            val hwnd: Int,
            val flags: Int,
            val insertAfter: Int,
            val owner: Int,
            val style: Int,
            val windowRect: Rect,
            val clientRect: Rect,
            val visibleRect: Rect,
        ) : HostMessage()

        data class SetParent(
            val hwnd: Int,
            val parent: Int,
            val scale: Float,
            val pid: Int,
        ) : HostMessage()
    }

    fun decode(opcode: Int, payload: ByteArray): HostMessage? {
        val buf = ByteBuffer.wrap(payload).order(ORDER)
        return when (opcode) {
            IOCTL_CREATE_WINDOW -> {
                requireSize(payload, SIZE_CREATE_WINDOW, "create_window")
                // ioctl_android_create_window then WineActivity pid
                val hwnd = buf.int
                val opengl = buf.int != 0
                val parent = buf.int
                val scale = buf.float
                val pid = buf.int
                HostMessage.CreateWindow(hwnd, opengl, parent, scale, pid)
            }
            IOCTL_DESTROY_WINDOW -> {
                requireSize(payload, SIZE_DESTROY_WINDOW, "destroy_window")
                val hwnd = buf.int
                buf.int // opengl unused for destroy dispatch
                HostMessage.DestroyWindow(hwnd)
            }
            IOCTL_WINDOW_POS_CHANGED -> {
                requireSize(payload, SIZE_WINDOW_POS_CHANGED, "window_pos_changed")
                // device.c order: hdr, window_rect, client_rect, visible_rect, style, flags, after, owner
                val hwnd = buf.int
                buf.int // hdr.opengl
                val windowRect = readRect(buf)
                val clientRect = readRect(buf)
                val visibleRect = readRect(buf)
                val style = buf.int
                val flags = buf.int
                val after = buf.int
                val owner = buf.int
                // WineActivity / bridge method arg order:
                // hwnd, flags, insert_after, owner, style, window, client, visible
                HostMessage.WindowPosChanged(
                    hwnd = hwnd,
                    flags = flags,
                    insertAfter = after,
                    owner = owner,
                    style = style,
                    windowRect = windowRect,
                    clientRect = clientRect,
                    visibleRect = visibleRect,
                )
            }
            IOCTL_SET_WINDOW_PARENT -> {
                requireSize(payload, SIZE_SET_WINDOW_PARENT, "set_window_parent")
                val hwnd = buf.int
                buf.int // opengl
                val parent = buf.int
                val scale = buf.float
                val pid = buf.int
                HostMessage.SetParent(hwnd, parent, scale, pid)
            }
            else -> null
        }
    }

    /** Encode host→unix SURFACE_CHANGED stub (no native handle yet). */
    fun encodeSurfaceChanged(hwnd: Int, opengl: Boolean, ready: Boolean): ByteArray {
        val payload =
            ByteBuffer.allocate(SIZE_SURFACE_CHANGED).order(ORDER).apply {
                putInt(hwnd)
                putInt(if (opengl) 1 else 0)
                putInt(if (ready) 1 else 0)
            }.array()
        return encodeFrame(HOST_SURFACE_CHANGED, payload)
    }

    fun encodeFrame(opcode: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ORDER).apply {
            putInt(opcode)
            putInt(payload.size)
            put(payload)
        }.array()

    private fun readRect(buf: ByteBuffer): Rect {
        val left = buf.int
        val top = buf.int
        val right = buf.int
        val bottom = buf.int
        return Rect(left, top, right, bottom)
    }

    private fun requireSize(payload: ByteArray, expected: Int, name: String) {
        check(payload.size >= expected) {
            "wineandroid $name payload too short: ${payload.size} < $expected"
        }
    }
}
