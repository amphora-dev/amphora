package app.amphora.sdk.internal.window

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.driver.WineDriver

/**
 * Mirrors Wine's window hierarchy as Android views, ported from Wine's
 * `WineActivity.java` (dlls/wineandroid.drv, LGPL-2.1-or-later).
 *
 * Every Wine window gets two [WindowGroup]s: the whole window (positioned at
 * its visible rect inside the parent's client group) and its client area.
 * Top-level windows and the desktop draw into a [WineView] (a TextureView)
 * whose surface Wine renders to; OpenGL windows get a second view for the
 * client area. Views are created with the application context, so the tree
 * can move to a new activity instance without being rebuilt.
 */
internal class WindowTree(
    private val context: Context,
    private val port: DriverPort,
    private val desktopWidth: Int,
    private val desktopHeight: Int,
    private val events: Events,
) : WineDriver.Listener {
    interface Events {
        /** Wine created its desktop; [view] should be shown, sized [desktopWidth]×[desktopHeight]. */
        fun onDesktop(view: TopView)

        /** The desktop view was laid out at its final size and Wine was told. */
        fun onDesktopSized(width: Int, height: Int)
    }

    private val windows = HashMap<Int, WineWindow>()
    private var messageWindow: WineWindow? = null

    /** Context for the views of the tree: the application's, so they outlive an activity. */
    val viewContext: Context get() = context

    var top: TopView? = null
        private set
    var desktop: WineWindow? = null
        private set
    var cursor: PointerIcon? = null
        private set

    /**
     * The desktop is a text editor only while the soft keyboard is asked for.
     * Otherwise no IME is bound to it, so hardware keys reach Wine as keys
     * instead of feeding an input method's composition (pinyin and the like).
     */
    var softKeyboard: Boolean = false

    /** The view that receives keys: the desktop's whole view. */
    val focusTarget: View? get() = desktop?.windowGroup?.contentView

    fun window(hwnd: Int): WineWindow? = windows[hwnd]

    internal fun register(window: WineWindow) {
        windows[window.hwnd] = window
    }

    internal fun unregister(hwnd: Int) {
        windows.remove(hwnd)
    }

    // WineDriver.Listener: called on the main thread.

    override fun onCreateDesktopView() {
        if (top != null) return
        val view = TopView(context, this, desktopWidth, desktopHeight)
        top = view
        messageWindow = WineWindow(this, HWND_MESSAGE, null).also { it.createWindowGroups() }
        events.onDesktop(view)
    }

    override fun onCreateWindow(hwnd: Int, isDesktop: Boolean, opengl: Boolean, parent: Int) {
        var win = windows[hwnd]
        if (win == null) {
            val top = top ?: return AmphoraLog.w("window ${Integer.toHexString(hwnd)} before the desktop view")
            win = WineWindow(this, hwnd, if (isDesktop) null else windows[parent])
            win.createWindowGroups()
            if (win.parent === desktop) win.createWholeView()
            if (isDesktop) {
                desktop = win
                top.addView(win.createWholeView())
                win.clientGroup?.bringToFront()
                win.windowGroup?.contentView?.requestFocus()
            }
        }
        if (opengl) win.createClientView()
    }

    override fun onDestroyWindow(hwnd: Int) {
        windows[hwnd]?.destroy()
    }

    override fun onWindowPosChanged(
        hwnd: Int,
        flags: Int,
        insertAfter: Int,
        owner: Int,
        style: Int,
        window: Rect,
        client: Rect,
        visible: Rect,
    ) {
        val win = windows[hwnd] ?: return
        win.posChanged(flags, insertAfter, style, client, visible)
        // A virtual desktop takes the size of the display mode a program sets; the view follows.
        if (win === desktop) top?.resize(visible.width(), visible.height())
    }

    override fun onSetParent(hwnd: Int, parent: Int) {
        val win = windows[hwnd] ?: return
        val newParent = windows[parent] ?: return
        win.setParent(newParent)
        if (win.parent === desktop) win.createWholeView()
    }

    override fun onSetCursor(id: Int, width: Int, height: Int, hotspotX: Int, hotspotY: Int, bits: IntArray?) {
        cursor =
            if (bits != null && width > 0 && height > 0) {
                PointerIcon.create(
                    Bitmap.createBitmap(bits, width, height, Bitmap.Config.ARGB_8888),
                    hotspotX.toFloat(),
                    hotspotY.toFloat(),
                )
            } else {
                PointerIcon.getSystemIcon(context, id)
            }
    }

    internal fun surfaceChanged(hwnd: Int, surface: Surface, opengl: Boolean) =
        port.surfaceChanged(hwnd, surface, opengl)

    internal fun motion(hwnd: Int, action: Int, x: Int, y: Int, buttons: Int, vscroll: Int): Boolean =
        port.motion(hwnd, action, x, y, buttons, vscroll)

    internal fun key(hwnd: Int, event: KeyEvent): Boolean = port.key(hwnd, event.action, event.keyCode, event.metaState)

    internal fun desktopSized(width: Int, height: Int) {
        port.desktopChanged(width, height)
        events.onDesktopSized(width, height)
    }

    companion object {
        const val HWND_MESSAGE: Int = -3 // 0xfffffffd
        const val SWP_NOZORDER: Int = 0x04
        const val WS_VISIBLE: Int = 0x10000000
    }
}

/** One Wine window. Children are kept in z-order, topmost first. */
internal class WineWindow(val tree: WindowTree, val hwnd: Int, parent: WineWindow?) {
    var parent: WineWindow? = parent
        private set
    val children = ArrayList<WineWindow>()
    var visible: Boolean = false
        private set
    private var visibleRect = Rect()
    private var clientRect = Rect()
    var windowGroup: WindowGroup? = null
        private set
    var clientGroup: WindowGroup? = null
        private set
    private var windowSurface: Surface? = null
    private var clientSurface: Surface? = null
    private var windowTexture: SurfaceTexture? = null
    private var clientTexture: SurfaceTexture? = null

    init {
        tree.register(this)
        parent?.children?.add(this)
    }

    fun destroy() {
        visible = false
        tree.unregister(hwnd)
        parent?.children?.remove(this)
        destroyWindowGroups()
    }

    fun createWindowGroups(): WindowGroup {
        clientGroup?.let { return it }
        val context = tree.viewContext
        val whole = WindowGroup(context, this)
        val client = WindowGroup(context, this)
        windowGroup = whole
        clientGroup = client
        whole.addView(client)
        client.setLayout(
            clientRect.left - visibleRect.left,
            clientRect.top - visibleRect.top,
            clientRect.right - visibleRect.left,
            clientRect.bottom - visibleRect.top,
        )
        parent?.let { parent ->
            parent.createWindowGroups()
            if (visible) addViewToParent()
            whole.setLayout(visibleRect.left, visibleRect.top, visibleRect.right, visibleRect.bottom)
        }
        return client
    }

    private fun destroyWindowGroups() {
        windowGroup?.let { whole ->
            if (parent?.clientGroup != null) removeViewFromParent()
            whole.destroyView()
        }
        clientGroup?.destroyView()
        windowGroup = null
        clientGroup = null
    }

    fun createWholeView(): View {
        val whole = windowGroup ?: run {
            createWindowGroups()
            windowGroup!!
        }
        whole.createView(isClient = false).layout(0, 0, visibleRect.width(), visibleRect.height())
        return whole
    }

    fun createClientView() {
        val client = clientGroup ?: createWindowGroups()
        client.createView(isClient = true).layout(0, 0, clientRect.width(), clientRect.height())
    }

    private fun addViewToParent() {
        val parent = parent ?: return
        val group = parent.clientGroup ?: return
        val whole = windowGroup ?: return
        var pos = group.childCount - 1
        // The parent's content view stays last.
        if (pos >= 0 && group.getChildAt(pos) === group.contentView) pos--
        for (child in parent.children) {
            if (pos < 0 || child === this) break
            if (!child.visible) continue
            if (child === (group.getChildAt(pos) as? WindowGroup)?.window) pos--
        }
        group.addView(whole, pos + 1)
    }

    private fun removeViewFromParent() {
        parent?.clientGroup?.removeView(windowGroup)
    }

    private fun setZOrder(prev: WineWindow?) {
        val siblings = parent?.children ?: return
        siblings.remove(this)
        val pos = if (prev != null) siblings.indexOf(prev) else -1
        siblings.add(pos + 1, this)
    }

    private fun syncViewsZOrder() {
        val parent = parent ?: return
        val group = parent.clientGroup ?: return
        var i = parent.children.size - 1
        var j = 0
        while (i >= 0) {
            val child = parent.children[i]
            if (!child.visible) {
                i--
                continue
            }
            val view = group.getChildAt(j) ?: break
            if (view === group.contentView) {
                i--
                continue
            }
            if (child !== (view as? WindowGroup)?.window) break
            j++
            i--
        }
        while (i >= 0) {
            val child = parent.children[i--]
            if (child.visible) child.windowGroup?.bringToFront()
        }
    }

    fun posChanged(flags: Int, insertAfter: Int, style: Int, client: Rect, visible: Rect) {
        val wasVisible = this.visible
        visibleRect = visible
        clientRect = client
        this.visible = (style and WindowTree.WS_VISIBLE) != 0
        val zorder = (flags and WindowTree.SWP_NOZORDER) == 0
        if (zorder && parent != null) setZOrder(tree.window(insertAfter))

        windowGroup?.let { whole ->
            whole.setLayout(visible.left, visible.top, visible.right, visible.bottom)
            if (parent != null) {
                when {
                    !wasVisible && this.visible -> addViewToParent()
                    wasVisible && !this.visible -> removeViewFromParent()
                    this.visible && zorder -> syncViewsZOrder()
                }
            }
        }
        clientGroup?.setLayout(
            client.left - visible.left,
            client.top - visible.top,
            client.right - visible.left,
            client.bottom - visible.top,
        )
    }

    fun setParent(newParent: WineWindow) {
        windowGroup?.let { whole ->
            if (visible) removeViewFromParent()
            newParent.createWindowGroups()
            whole.setLayout(visibleRect.left, visibleRect.top, visibleRect.right, visibleRect.bottom)
        }
        parent?.children?.remove(this)
        parent = newParent
        newParent.children.add(this)
        if (visible && windowGroup != null) addViewToParent()
    }

    fun setSurface(texture: SurfaceTexture?, isClient: Boolean) {
        if (isClient) {
            if (texture == null) {
                clientSurface = null
            } else if (texture !== clientTexture) {
                clientTexture = texture
                clientSurface = Surface(texture)
            }
            clientSurface?.let { tree.surfaceChanged(hwnd, it, true) }
        } else {
            if (texture == null) {
                windowSurface = null
            } else if (texture !== windowTexture) {
                windowTexture = texture
                windowSurface = Surface(texture)
            }
            windowSurface?.let { tree.surfaceChanged(hwnd, it, false) }
        }
    }

    /** Event position in the parent's client coordinates (desktop coordinates for top-level windows). */
    fun eventX(event: MotionEvent): Int = Math.round(event.x + (windowGroup?.left ?: 0))

    fun eventY(event: MotionEvent): Int = Math.round(event.y + (windowGroup?.top ?: 0))

    /** Top-level windows and the desktop take input; children leave it to them. */
    val takesInput: Boolean get() = parent == null || parent === tree.desktop
}

/** A Wine window's whole or client area, optionally holding its [WineView]. */
@SuppressLint("ViewConstructor")
internal class WindowGroup(context: Context, val window: WineWindow) : ViewGroup(context) {
    var contentView: WineView? = null
        private set

    init {
        visibility = VISIBLE
    }

    /** layout(), but never empty: a zero-sized view gets no surface. */
    fun setLayout(left: Int, top: Int, right: Int, bottom: Int) {
        layout(left, top, if (right <= left + 1) left + 2 else right, if (bottom <= top + 1) top + 2 else bottom)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        contentView?.layout(0, 0, right - left, bottom - top)
    }

    fun createView(isClient: Boolean): WineView {
        contentView?.let { return it }
        val view = WineView(context, window, isClient)
        addView(view)
        contentView = view
        return view
    }

    fun destroyView() {
        contentView?.let { removeView(it) }
        contentView = null
    }
}

/** A surface Wine draws one window (or its OpenGL client area) into. */
@SuppressLint("ViewConstructor")
internal class WineView(context: Context, val window: WineWindow, private val isClient: Boolean) :
    TextureView(context),
    TextureView.SurfaceTextureListener {
    private val tree: WindowTree get() = window.tree

    init {
        surfaceTextureListener = this
        visibility = VISIBLE
        isOpaque = false
        isFocusable = !isClient
        isFocusableInTouchMode = !isClient
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        window.setSurface(surface, isClient)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        window.setSurface(surface, isClient)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        window.setSurface(null, isClient)
        // Keep the texture: Wine may still be drawing into it.
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? =
        tree.cursor ?: super.onResolvePointerIcon(event, pointerIndex)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isClient || !window.takesInput) return false
        if (event.source and InputDevice.SOURCE_CLASS_POINTER == 0) return super.onGenericMotionEvent(event)
        // Some devices also report the primary button through touch down/up;
        // passing both gives Wine duplicate clicks and a confused button state.
        val masked = event.actionMasked
        if ((masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) &&
            event.actionButton == MotionEvent.BUTTON_PRIMARY
        ) {
            return true
        }
        return tree.motion(
            window.hwnd,
            event.action,
            window.eventX(event),
            window.eventY(event),
            event.buttonState,
            event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt(),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isClient || !window.takesInput) return false
        return tree.motion(window.hwnd, event.action, window.eventX(event), window.eventY(event), event.buttonState, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in SYSTEM_KEYS) return super.dispatchKeyEvent(event)
        return tree.key(window.hwnd, event) || super.dispatchKeyEvent(event)
    }

    /** The desktop's view is where the soft keyboard types, while it is asked for. */
    override fun onCheckIsTextEditor(): Boolean = !isClient && window === tree.desktop && tree.softKeyboard

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!onCheckIsTextEditor()) return null
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return KeyInputConnection(this)
    }

    private companion object {
        val SYSTEM_KEYS =
            setOf(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
            )
    }
}

/**
 * Soft keyboard → key events. Wine's driver takes key codes, not text, so
 * committed text goes out as the key events that type it (BaseInputConnection
 * does this for non-editor views), and deletions become DEL presses.
 */
private class KeyInputConnection(view: View) : BaseInputConnection(view, false) {
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { press(KeyEvent.KEYCODE_DEL) }
        repeat(afterLength) { press(KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    private fun press(code: Int) {
        sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}

/**
 * The root of the Wine view tree, exactly the size of the Wine desktop. The
 * host scales it to the screen; Android maps touch coordinates back through
 * the scale, so Wine always sees desktop pixels.
 */
@SuppressLint("ViewConstructor")
internal class TopView(context: Context, private val tree: WindowTree, width: Int, height: Int) :
    ViewGroup(context) {
    private var desktopWidth = width
    private var desktopHeight = height

    /** Takes the Wine desktop window's size, which changes with the display mode. */
    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == desktopWidth && height == desktopHeight)) return
        desktopWidth = width
        desktopHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(desktopWidth, desktopHeight)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        tree.desktopSized(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) =
        Unit // Children are placed by Wine's window positions.
}
