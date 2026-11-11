package app.amphora.sdk.internal.driver

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.Keep
import androidx.annotation.MainThread
import java.io.File

/**
 * The JNI surface of Wine's Android driver (`wineandroid.so`).
 *
 * The library binds itself to this class by name: `JNI_OnLoad` registers the
 * `wine_*` natives on `app/amphora/sdk/internal/driver/WineDriver`, and
 * [wine_init] keeps a reference to the object and calls its callbacks by name
 * and signature. Renaming anything here breaks the driver at runtime; R8 is
 * told to keep it (consumer-rules.pro). Kotlin `internal` members get mangled
 * names, so natives and callbacks are public members of this internal object.
 *
 * Per process, the driver can start once: it binds the abstract socket
 * `\Device\WineAndroid` and aborts if that fails, and it `_exit`s the process
 * when the Wine desktop disconnects. Hence one `:wine` process per session.
 *
 * Callbacks arrive on the main thread (the driver's fd is on the main
 * looper) and are posted, not run inline, so that the window tree is never
 * re-entered from inside a native call.
 */
@Keep
@Suppress("ktlint:standard:function-naming", "FunctionName")
internal object WineDriver {
    /** Receives the driver's callbacks, on the main thread. */
    interface Listener {
        fun onCreateDesktopView()

        fun onCreateWindow(hwnd: Int, isDesktop: Boolean, opengl: Boolean, parent: Int)

        fun onDestroyWindow(hwnd: Int)

        fun onWindowPosChanged(
            hwnd: Int,
            flags: Int,
            insertAfter: Int,
            owner: Int,
            style: Int,
            window: Rect,
            client: Rect,
            visible: Rect,
        )

        fun onSetParent(hwnd: Int, parent: Int)

        fun onSetCursor(id: Int, width: Int, height: Int, hotspotX: Int, hotspotY: Int, bits: IntArray?)
    }

    private val main = Handler(Looper.getMainLooper())

    /** Unix libraries, in dependency order. */
    private val libraries = listOf("ntdll.so", "win32u.so", "wineandroid.so")

    @Volatile var loaded: Boolean = false
        private set

    var started: Boolean = false
        private set

    @Volatile private var listener: Listener? = null

    /**
     * Loads the driver's Unix libraries from [unixLibDir]. Blocking; call off
     * the main thread. They come from the installed runtime, not the APK: the
     * installer checked the archive's SHA-256 against the recipe and every
     * file against its manifest.
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun load(unixLibDir: File) {
        if (loaded) return
        for (lib in libraries) System.load(File(unixLibDir, lib).path)
        loaded = true
    }

    /** Starts the driver's IPC on the main looper. Once per process. */
    @MainThread
    fun start(listener: Listener) {
        check(loaded) { "driver not loaded" }
        check(!started) { "driver already started in this process" }
        this.listener = listener
        wine_init()
        started = true
    }

    // Natives, registered by wineandroid.so's JNI_OnLoad.

    private external fun wine_init()

    external fun wine_desktop_changed(width: Int, height: Int)

    external fun wine_config_changed(dpi: Int)

    external fun wine_surface_changed(hwnd: Int, surface: Surface?, opengl: Boolean)

    external fun wine_motion_event(hwnd: Int, action: Int, x: Int, y: Int, state: Int, vscroll: Int): Boolean

    external fun wine_keyboard_event(hwnd: Int, action: Int, keycode: Int, state: Int): Boolean

    // Callbacks, looked up by name and signature by wine_init.

    fun createDesktopView() {
        post { it.onCreateDesktopView() }
    }

    fun createWindow(hwnd: Int, isDesktop: Boolean, opengl: Boolean, parent: Int) {
        post { it.onCreateWindow(hwnd, isDesktop, opengl, parent) }
    }

    fun destroyWindow(hwnd: Int) {
        post { it.onDestroyWindow(hwnd) }
    }

    fun setParent(hwnd: Int, parent: Int) {
        post { it.onSetParent(hwnd, parent) }
    }

    fun setCursor(id: Int, width: Int, height: Int, hotspotX: Int, hotspotY: Int, bits: IntArray?) {
        post { it.onSetCursor(id, width, height, hotspotX, hotspotY, bits) }
    }

    @Suppress("LongParameterList")
    fun windowPosChanged(
        hwnd: Int,
        flags: Int,
        insertAfter: Int,
        owner: Int,
        style: Int,
        windowLeft: Int,
        windowTop: Int,
        windowRight: Int,
        windowBottom: Int,
        clientLeft: Int,
        clientTop: Int,
        clientRight: Int,
        clientBottom: Int,
        visibleLeft: Int,
        visibleTop: Int,
        visibleRight: Int,
        visibleBottom: Int,
    ) {
        val window = Rect(windowLeft, windowTop, windowRight, windowBottom)
        val client = Rect(clientLeft, clientTop, clientRight, clientBottom)
        val visible = Rect(visibleLeft, visibleTop, visibleRight, visibleBottom)
        post { it.onWindowPosChanged(hwnd, flags, insertAfter, owner, style, window, client, visible) }
    }

    private inline fun post(crossinline call: (Listener) -> Unit) {
        main.post { listener?.let(call) }
    }
}
