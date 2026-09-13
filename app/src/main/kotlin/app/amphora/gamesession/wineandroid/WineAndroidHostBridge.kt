package app.amphora.gamesession.wineandroid

import android.graphics.Rect
import android.net.LocalServerSocket
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Kotlin stand-in for WineActivity's public HWND/Surface surface — **no JNI**.
 *
 * Field / method names mirror pin `WineActivity.java` and
 * `dlls/wineandroid.drv/device.c` ioctl structs. The unix driver today calls
 * those Java methods in-process; Amphora runs Wine via box64 exec in `:session`,
 * so the durable contract is the same messages over a host socket (layout TBD
 * against the ioctl structs — do not invent binary packing here).
 *
 * ioctl ops (device.c `enum android_ioctl`):
 * - IOCTL_CREATE_WINDOW      -> createWindow(hwnd, opengl, parent, scale, pid)
 * - IOCTL_DESTROY_WINDOW     -> destroyWindow(hwnd)
 * - IOCTL_WINDOW_POS_CHANGED -> windowPosChanged(hwnd, flags, insert_after, owner, style, rects…)
 * - IOCTL_SET_WINDOW_PARENT  -> setParent(hwnd, parent, scale, pid)
 * - plus buffer/query/cursor ops that stay on the unix side once ANativeWindow is registered
 *
 * ioctl payloads (names only — copy sizes from device.c when wiring the socket):
 * - ioctl_header { hwnd, opengl }
 * - ioctl_android_create_window { hdr, parent, scale }
 * - ioctl_android_destroy_window { hdr }
 * - ioctl_android_window_pos_changed { hdr, window_rect, client_rect, visible_rect, style, flags, after, owner }
 * - ioctl_android_set_window_parent { hdr, parent, scale }
 *
 * Surface feedback replaces JNI `wine_surface_changed(hwnd, surface, opengl)`.
 */
class WineAndroidHostBridge(
    private val activity: ComponentActivity,
    private val desktop: WineAndroidDesktop,
    private val onSurfaceChanged: (hwnd: Int, surface: Surface, opengl: Boolean) -> Unit,
) {
    private val windows = LinkedHashMap<Int, WineAndroidWindow>()
    private val socketStarted = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun createDesktopWindow(hwnd: Int) {
        Log.i(TAG, "createDesktopWindow hwnd=$hwnd")
        // Desktop HWND is the Activity root; per-HWND SurfaceViews attach as children.
    }

    fun createWindow(hwnd: Int, opengl: Boolean, parent: Int, scale: Float, pid: Int) {
        Log.i(TAG, "createWindow hwnd=$hwnd opengl=$opengl parent=$parent scale=$scale pid=$pid")
        activity.runOnUiThread {
            val window =
                WineAndroidWindow(
                    hwnd = hwnd,
                    parentHwnd = parent,
                    isClient = opengl,
                    scale = scale,
                )
            windows[hwnd] = window
            desktop.attachWindow(window) { attachedHwnd, surface ->
                onSurfaceChanged(attachedHwnd, surface, opengl)
            }
        }
    }

    fun destroyWindow(hwnd: Int) {
        Log.i(TAG, "destroyWindow hwnd=$hwnd")
        activity.runOnUiThread {
            windows.remove(hwnd)
            desktop.detachWindow(hwnd)
        }
    }

    fun setParent(hwnd: Int, parent: Int, scale: Float, pid: Int) {
        Log.i(TAG, "setParent hwnd=$hwnd parent=$parent scale=$scale pid=$pid")
        activity.runOnUiThread {
            val existing = windows[hwnd] ?: return@runOnUiThread
            val updated = existing.copy(parentHwnd = parent, scale = scale)
            windows[hwnd] = updated
            desktop.updateWindow(updated)
        }
    }

    fun windowPosChanged(
        hwnd: Int,
        flags: Int,
        insertAfter: Int,
        owner: Int,
        style: Int,
        windowRect: Rect,
        clientRect: Rect,
        visibleRect: Rect,
    ) {
        Log.i(
            TAG,
            "windowPosChanged hwnd=$hwnd flags=$flags after=$insertAfter owner=$owner " +
                "style=$style window=$windowRect client=$clientRect visible=$visibleRect",
        )
        activity.runOnUiThread {
            val existing = windows[hwnd] ?: return@runOnUiThread
            val updated =
                existing.copy().also {
                    it.windowRect = Rect(windowRect)
                    it.clientRect = Rect(clientRect)
                }
            windows[hwnd] = updated
            desktop.updateWindow(updated)
        }
    }

    /**
     * Bind a filesystem unix-domain socket under the session files dir.
     * Accept loop is a stub until the WCP wineandroid.drv side speaks this path
     * instead of JNI RegisterNatives.
     */
    fun startSocketStub(socketFile: File) {
        if (!socketStarted.compareAndSet(false, true)) return
        socketFile.parentFile?.mkdirs()
        if (socketFile.exists()) socketFile.delete()
        // LocalServerSocket(name) uses the abstract namespace when name has no path separator
        // semantics on some APIs; prefer creating the listen name from the file's absolute path
        // only after the unix driver agrees on the path. For now: create the path marker + log.
        try {
            socketFile.createNewFile()
        } catch (e: IOException) {
            Log.w(TAG, "Could not create socket marker ${socketFile.absolutePath}", e)
        }
        Log.i(
            TAG,
            "wineandroid host socket stub at ${socketFile.absolutePath} " +
                "(accept/decode of ioctl_android_* frames is TODO once WCP ships wineandroid.drv)",
        )
        acceptThread =
            thread(name = "WineAndroidHostBridge", isDaemon = true) {
                try {
                    // Abstract-namespace placeholder so we exercise bind without racing the
                    // filesystem path the driver will eventually use.
                    LocalServerSocket("amphora-wineandroid-stub").use { server ->
                        serverSocket = server
                        Log.i(TAG, "LocalServerSocket amphora-wineandroid-stub listening (stub)")
                        while (!Thread.currentThread().isInterrupted) {
                            try {
                                server.accept().close()
                                Log.i(TAG, "stub accept: peer connected then closed (no protocol yet)")
                            } catch (e: IOException) {
                                if (Thread.currentThread().isInterrupted) break
                                Log.w(TAG, "stub accept failed", e)
                                break
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "socket stub listen failed", e)
                } finally {
                    serverSocket = null
                }
            }
    }

    fun close() {
        acceptThread?.interrupt()
        acceptThread = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        socketStarted.set(false)
        activity.runOnUiThread {
            windows.keys.toList().forEach { desktop.detachWindow(it) }
            windows.clear()
        }
    }

    private companion object {
        const val TAG = "WineAndroidHostBridge"
    }
}
