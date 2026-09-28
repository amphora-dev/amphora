package app.amphora.gamesession.wineandroid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import app.amphora.gamesession.input.ImeUiState
import app.amphora.gamesession.input.WineInputConnection
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Kotlin desktop shaped like upstream [WineActivity] window groups:
 *
 * - Root [FrameLayout] letterboxes an inner [contentHost] sized guest×hostScale.
 * - Each HWND is a [WindowGroup] (FrameLayout, draws nothing) plus nested child
 *   WindowGroups. Its buffer is a [SurfaceControl] layer under one container
 *   SurfaceView ([layerHost]); [syncLayers] gives every layer its z (the view
 *   tree's drawing order, i.e. the Win32 stack), frame and ancestor clip.
 * - Layout uses **visibleRect** (parent-relative), not windowRect alone; the
 *   client (Vulkan/GL) group of an hwnd uses **clientRect** in the same space.
 * - The layer buffer stays at guest px (min 2×2); after a size change we
 *   re-invoke onSurface so native re-registers and sends SURFACE_CHANGED with
 *   the new w/h (upstream TextureView size-changed path).
 * - **Defer first** nativeRegisterSurface until guest rects have a real
 *   positive w×h from WINDOW_POS / create (not the artificial MIN 2×2 alone).
 *   After the first successful register, keep re-binding on size changes.
 * - Touch: [WindowGroup] → [WineAndroidNative.nativeSendMotionEvent]
 *   (MOTION_EVENT on the desktop event pipe). Coords = contentHost-local host px /
 *   [hostScale] → guest desktop px. Not X inject.
 * - Keys: focusable GDI [WindowGroup] (upstream WineView) + Activity
 *   dispatchKeyEvent → [WineAndroidNative.nativeSendKeyboardEvent]
 *   (KEYBOARD_EVENT). Hardware KEYCODE_* / `adb input keyevent|text`.
 * - Soft IME: [onCreateInputConnection] → [WineInputConnection]; committed
 *   ASCII/Latin maps via `KeyCharacterMap` → same [sendKeyboardEvent] pipe.
 *   Unmapped code points (CJK / emoji) → [nativeSendUnicodeChar]
 *   (KEYEVENTF_UNICODE on EVENT_KEYBOARD). Composition stays host-local
 *   ([ImeUiState] chip); no IMM32/TSF into guest.
 *   **Policy**: touch DOWN does **not** auto-show IME
 *   ([WineAndroidImeUi.shouldAutoShowSoftKeyboardOnTouch] = false). Focus alone
 *   must not open soft IME: [onCheckIsTextEditor] only while [imeWanted]
 *   (set in [showSoftKeyboard], cleared in [hideSoftKeyboard]) — FrameLayout
 *   has no TextView `setShowSoftInputOnFocus`. Explicit [showSoftKeyboard]
 *   uses IMM.showSoftInput with serve-ready retries (session chip /
 *   [toggleSoftKeyboard] / letterbox long-press / debug IME_SHOW).
 *   [hideSoftKeyboard] on pause / focus loss.
 *   Debug: [injectCommittedTextForDebug] reuses the same commit path (HA262 smoke).
 * - Style / z-order: [WineAndroidWindowStack] tracks WS_VISIBLE + sibling order;
 *   invisible HWND groups are removed from the parent (upstream add/remove), and
 *   !(flags & SWP_NOZORDER) syncs bringChildToFront bottom→top.
 *   Debug [dumpZOrderForDebug] / [injectZOrderTopForDebug] + `DUMP_ZORDER` /
 *   `ZORDER_TOP_HWND` extras for overlap eye-check; sync logs when ≥2 visible
 *   siblings ([WineAndroidDebugZOrderInject.hasOverlapCandidates]).
 * - Capture: [setCapture] remembers hwnd (0 = release); touch / generic motion
 *   address capture hwnd when set ([WineAndroidCaptureTarget]), else hit-test.
 *   Debug [injectCaptureForDebug] / `CAPTURE_HWND` extra for HA262 smoke without
 *   title-bar drag.
 * - Cursor: [setCursor] → Android [PointerIcon] (TYPE_NULL hide, system id, or
 *   custom ARGB bits). No separate cursor overlay View.
 */
class WineAndroidDesktop(context: Context) : FrameLayout(context) {
    private data class Key(val hwnd: Int, val client: Boolean)

    private val contentHost =
        FrameLayout(context).also {
            addView(it, LayoutParams(0, 0))
        }

    /**
     * Never draws; its SurfaceControl parents every window layer so the
     * stacking between HWND buffers can be set explicitly ([syncLayers]).
     * Sibling SurfaceViews would be ordered by SurfaceFlinger sub-layer and
     * creation order instead of the Win32 stack.
     */
    private val layerHost =
        SurfaceView(context).also { view ->
            view.holder.setFormat(PixelFormat.TRANSLUCENT)
            view.holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onLayerRootCreated(view.surfaceControl)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onLayerRootDestroyed()
                    }
                },
            )
            contentHost.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    /** [layerHost]'s SurfaceControl while its surface exists. */
    private var layerRoot: SurfaceControl? = null

    /** Last logged stacking, so [syncLayers] only logs when it changes. */
    private var lastLayerOrder: List<String> = emptyList()

    /**
     * HWND that should own the next KEYBOARD_EVENT (last GDI group that took
     * a touch, else desktop hwnd). Guest still injects via hwnd 0; this is
     * for wire/log parity with upstream WineView.
     */
    @Volatile private var keyTargetHwnd: Int = 0

    /** Queued debug IME inject until [desktopHwnd] / [keyTargetHwnd] is known. */
    @Volatile private var pendingDebugImeText: String? = null

    /**
     * Queued debug CAPTURE_HWND inject when sentinel needs [desktopHwnd]
     * ([WineAndroidDebugCaptureInject.SENTINEL_DESKTOP]).
     */
    @Volatile private var pendingDebugCaptureRequested: Int? = null

    /** Host-local composing / keyboard chip (not sent to guest). */
    private var imeUiState = ImeUiState()
    private var imeUiStateListener: ((ImeUiState) -> Unit)? = null

    private val groups = LinkedHashMap<Key, WindowGroup>()

    /**
     * Per-parent sibling HWND order (top-first). Key 0 = contentHost top-level
     * (parentHwnd 0 / desktop). Nested groups key by parent HWND.
     */
    private val siblingStacks = HashMap<Int, MutableList<Int>>()

    /**
     * True only while an explicit soft-IME show is in effect. Gates
     * [onCheckIsTextEditor] so focus / tap does not make IMM treat this view
     * as an editor (HA262: mInputShown stayed true after requestFocus alone).
     */
    @Volatile private var imeWanted: Boolean = false

    /** Pending [showSoftKeyboard] serve-ready retries; cleared on success / hide. */
    private val softImeShowRetryRunnables = ArrayList<Runnable>()

    init {
        // Upstream WineView: GDI views are focusable so KeyEvents land here.
        isFocusable = true
        isFocusableInTouchMode = true
        // Soft IME: FrameLayout has no TextView.setShowSoftInputOnFocus;
        // [imeWanted] gates [onCheckIsTextEditor] so focus/tap does not open IME.
        // Letterbox (this view minus contentHost) long-press toggles IME;
        // WindowGroups consume their own touches so guest long-press is intact.
        isLongClickable = true
        setOnLongClickListener {
            toggleSoftKeyboard()
            true
        }
    }

    /** Guest / Wine desktop size (explorer /desktop=shell,WxH). */
    private var guestDesktopWidth: Int = 0
    private var guestDesktopHeight: Int = 0

    /** Uniform scale + letterbox offsets mapping guest px → this view's px. */
    private var hostScale: Float = 1f
    private var offsetX: Int = 0
    private var offsetY: Int = 0

    /** Desktop HWND from createWindow(isDesktop=true); children of it are top-level. */
    private var desktopHwnd: Int = 0

    /**
     * Win32 mouse capture HWND from IOCTL_SET_CAPTURE (0 = released).
     * MOTION / generic motion use this when non-zero instead of the hit view.
     */
    @Volatile private var captureHwnd: Int = 0

    /** Host pointer from IOCTL_SET_CURSOR; null until first setCursor. */
    @Volatile private var currentPointerIcon: PointerIcon? = null

    fun setGuestDesktopSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == guestDesktopWidth && height == guestDesktopHeight) return
        guestDesktopWidth = width
        guestDesktopHeight = height
        recalculateScale("setGuestDesktopSize")
        relayoutAll()
    }

    fun setDesktopHwnd(hwnd: Int) {
        desktopHwnd = hwnd
        if (keyTargetHwnd == 0) keyTargetHwnd = hwnd
        pendingDebugImeText?.let { pending ->
            pendingDebugImeText = null
            post { injectCommittedTextForDebug(pending) }
        }
        pendingDebugCaptureRequested?.let { pending ->
            pendingDebugCaptureRequested = null
            post { injectCaptureForDebug(pending) }
        }
    }

    /**
     * Remember SetCapture hwnd for MOTION routing (0 releases).
     * Call on the UI thread.
     */
    fun setCapture(hwnd: Int) {
        captureHwnd = hwnd
        Log.i(TAG, "capture hwnd=$hwnd")
    }

    /**
     * Debug / HA262 smoke: force host capture without guest IOCTL_SET_CAPTURE
     * (title-bar drag). [WineAndroidDebugCaptureInject.SENTINEL_DESKTOP] (`-1`)
     * resolves to [desktopHwnd] once known. Call only from FLAG_DEBUGGABLE
     * session code.
     */
    fun injectCaptureForDebug(requested: Int) {
        val resolved =
            WineAndroidDebugCaptureInject.resolveCaptureTarget(
                requested = requested,
                desktopHwnd = desktopHwnd,
            )
        if (resolved == null) {
            Log.i(
                TAG,
                "capture inject deferred (desktop hwnd not ready) requested=$requested",
            )
            pendingDebugCaptureRequested = requested
            return
        }
        Log.i(
            TAG,
            "capture inject requested=$requested resolved=$resolved " +
                "(debug; host-only, not guest IOCTL)",
        )
        setCapture(resolved)
    }

    /**
     * Debug / HA262 smoke: log all sibling stacks (top-first; `*` = visible).
     * Call only from FLAG_DEBUGGABLE session code.
     */
    fun dumpZOrderForDebug() {
        val keys = siblingStacks.keys.sorted()
        if (keys.isEmpty()) {
            Log.i(TAG, "zorder dump (no sibling stacks)")
            return
        }
        for (key in keys) {
            val stack = siblingStacks[key] ?: continue
            Log.i(
                TAG,
                WineAndroidDebugZOrderInject.formatStackLine(
                    parentKey = key,
                    topFirst = stack.toList(),
                    visible = { hwnd -> isHwndVisibleInStack(hwnd) },
                ),
            )
        }
    }

    /**
     * Debug / HA262 smoke: force [hwnd] to HWND_TOP under its parent, sync
     * Views, then dump stacks for eye-check. Unknown hwnd → warn + dump only.
     */
    fun injectZOrderTopForDebug(hwnd: Int) {
        val group =
            groups.values.firstOrNull { it.window.hwnd == hwnd && !it.window.isClient }
                ?: groups.values.firstOrNull { it.window.hwnd == hwnd }
        if (group == null) {
            Log.w(TAG, "zorder top inject hwnd=0x${hwnd.toString(16)} not attached; dump only")
            dumpZOrderForDebug()
            return
        }
        val stackKey = stackKeyFor(group.window.parentHwnd)
        Log.i(
            TAG,
            "zorder top inject hwnd=0x${hwnd.toString(16)} parentKey=$stackKey",
        )
        applyZOrder(hwnd, WineAndroidWindowStack.HWND_TOP, stackKey, reason = "inject-top")
        dumpZOrderForDebug()
    }

    private fun isHwndVisibleInStack(hwnd: Int): Boolean =
        groups.values.any { it.window.hwnd == hwnd && it.window.visible && it.parent != null }

    /**
     * Apply host [PointerIcon] from wineandroid setCursor ioctl.
     * Call on the UI thread. API 24+ only (upstream WineActivity gate).
     */
    fun setCursor(id: Int, width: Int, height: Int, hotspotX: Int, hotspotY: Int, bits: IntArray?) {
        val spec = WineAndroidCursorSpec.classify(id, width, height, hotspotX, hotspotY, bits)
        val icon =
            when (spec) {
                is WineAndroidCursorSpec.System ->
                    PointerIcon.getSystemIcon(context, spec.id)
                is WineAndroidCursorSpec.Custom -> {
                    val bitmap =
                        Bitmap.createBitmap(
                            spec.bits,
                            spec.width,
                            spec.height,
                            Bitmap.Config.ARGB_8888,
                        )
                    PointerIcon.create(bitmap, spec.hotspotX.toFloat(), spec.hotspotY.toFloat())
                }
            }
        currentPointerIcon = icon
        applyPointerIcon(icon)
        when (spec) {
            is WineAndroidCursorSpec.System ->
                Log.i(TAG, "cursor system id=${spec.id} (0=TYPE_NULL hide)")
            is WineAndroidCursorSpec.Custom ->
                Log.i(
                    TAG,
                    "cursor custom ${spec.width}x${spec.height} " +
                        "hotspot=${spec.hotspotX},${spec.hotspotY}",
                )
        }
    }

    private fun applyPointerIcon(icon: PointerIcon) {
        pointerIcon = icon
        contentHost.pointerIcon = icon
        groups.values.forEach { group -> group.pointerIcon = icon }
    }

    fun sendKeyboardEvent(event: KeyEvent): Boolean {
        val hwnd = if (keyTargetHwnd != 0) keyTargetHwnd else desktopHwnd
        val ok =
            WineAndroidNative.nativeSendKeyboardEvent(
                hwnd,
                event.action,
                event.keyCode,
                event.metaState,
            )
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val pass = WineAndroidKeyPassThrough.passThroughLabel(event.keyCode, ok)
            Log.i(
                TAG,
                "key hwnd=$hwnd action=${event.action} keycode=${event.keyCode} " +
                    "(${KeyEvent.keyCodeToString(event.keyCode)}) meta=${event.metaState} ok=$ok" +
                    (if (pass != null) " passThrough=$pass" else ""),
            )
        }
        return ok
    }

    override fun onCheckIsTextEditor(): Boolean = WineAndroidImeUi.shouldReportAsTextEditor(imeWanted)

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions =
            EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return WineInputConnection(
            this,
            object : WineInputConnection.Listener {
                override fun onCommitText(text: CharSequence) {
                    commitImeText(text)
                }

                override fun onDelete(beforeLength: Int, afterLength: Int) {
                    val backspaces =
                        beforeLength.coerceAtMost(WineAndroidImeCommit.MAX_IME_DELETE_COUNT)
                    val deletes =
                        afterLength.coerceAtMost(WineAndroidImeCommit.MAX_IME_DELETE_COUNT)
                    repeat(backspaces) {
                        for (event in WineAndroidImeCommit.tapKeyEvents(KeyEvent.KEYCODE_DEL)) {
                            sendKeyboardEvent(event)
                        }
                    }
                    repeat(deletes) {
                        for (event in WineAndroidImeCommit.tapKeyEvents(KeyEvent.KEYCODE_FORWARD_DEL)) {
                            sendKeyboardEvent(event)
                        }
                    }
                }

                override fun onSendKeyEvent(event: KeyEvent): Boolean {
                    sendKeyboardEvent(event)
                    return true
                }

                override fun onEditorAction() {
                    for (event in WineAndroidImeCommit.tapKeyEvents(KeyEvent.KEYCODE_ENTER)) {
                        sendKeyboardEvent(event)
                    }
                }

                override fun onComposingTextChanged(text: CharSequence) {
                    val composing = text.toString()
                    Log.d(
                        TAG,
                        "IME composing len=${composing.length} (host-local; not sent to guest)",
                    )
                    updateImeUiState(composingText = composing)
                }
            },
        )
    }

    /**
     * Debug / HA262 smoke: inject committed text on the same path as soft IME
     * [WineInputConnection] onCommitText (KeyCharacterMap + unicode fallback).
     * Call only from FLAG_DEBUGGABLE session code.
     */
    fun injectCommittedTextForDebug(text: CharSequence) {
        val payload = text.toString()
        if (payload.isEmpty()) return
        val hwnd = if (keyTargetHwnd != 0) keyTargetHwnd else desktopHwnd
        if (hwnd == 0) {
            Log.i(TAG, "IME unicode inject deferred (no hwnd yet) text='$payload'")
            pendingDebugImeText = payload
            return
        }
        Log.i(TAG, "IME unicode inject hwnd=$hwnd text='$payload' len=${payload.length}")
        commitImeText(payload)
    }

    /**
     * Debug / HA262 smoke: set host-local composing chip only (no guest pipe).
     * Empty [text] clears the chip. Call only from FLAG_DEBUGGABLE session code.
     */
    fun injectComposingTextForDebug(text: CharSequence) {
        val payload = text.toString()
        Log.i(
            TAG,
            "IME composing inject len=${payload.length} (host-local; not sent to guest)",
        )
        updateImeUiState(composingText = payload)
    }

    /** Shared soft-IME / debug commit → KEYBOARD_EVENT + KEYEVENTF_UNICODE. */
    private fun commitImeText(text: CharSequence) {
        val mapped = WineAndroidImeCommit.mapCommittedText(text)
        for (event in mapped.events) {
            sendKeyboardEvent(event)
        }
        val hwnd = if (keyTargetHwnd != 0) keyTargetHwnd else desktopHwnd
        for (codePoint in mapped.unmappedCodePoints) {
            val ok = WineAndroidNative.nativeSendUnicodeChar(hwnd, codePoint)
            Log.i(
                TAG,
                "IME unicode hwnd=$hwnd codePoint=U+${codePoint.toString(16)} ok=$ok",
            )
        }
    }

    /**
     * Explicit soft-IME show (session chip / letterbox long-press).
     * Not called from touch DOWN —
     * see [WineAndroidImeUi.shouldAutoShowSoftKeyboardOnTouch].
     * Sets [imeWanted] so [onCheckIsTextEditor] is true while IMM binds.
     *
     * Retries `requestFocus` + `restartInput` + `showSoftInput` on a bounded
     * schedule until IMM has served this view / window has focus (cold-start
     * `IME_SHOW` can race before servedView is set). Stops when [imeWanted]
     * clears or an attempt is accepted.
     */
    fun showSoftKeyboard() {
        imeWanted = true
        Log.i(TAG, "IME soft keyboard show")
        updateImeUiState(keyboardVisible = true)
        clearSoftImeShowRetries()
        val delays = WineAndroidImeUi.softImeShowRetryDelaysMs()
        for (index in delays.indices) {
            val attempt = index
            val delayMs = delays[attempt]
            val runnable = Runnable { runSoftImeShowAttempt(attempt, delays) }
            softImeShowRetryRunnables.add(runnable)
            if (delayMs <= 0L) {
                post(runnable)
            } else {
                postDelayed(runnable, delayMs)
            }
        }
    }

    private fun runSoftImeShowAttempt(attempt: Int, delays: LongArray) {
        if (!imeWanted) return
        if (tryShowSoftInputOnce()) {
            Log.i(TAG, "IME soft keyboard show served attempt=$attempt")
            clearSoftImeShowRetries()
            return
        }
        Log.i(TAG, "IME soft keyboard show not served yet attempt=$attempt")
        if (!WineAndroidImeUi.shouldScheduleSoftImeShowRetry(
                imeWanted = true,
                attemptAccepted = false,
                attemptIndex = attempt,
                delaysMs = delays,
            )
        ) {
            Log.w(TAG, "IME soft keyboard show exhausted retries")
        }
    }

    /**
     * One IMM show attempt. Returns true when [InputMethodManager.showSoftInput]
     * accepts or the view is already the active/served editor.
     */
    private fun tryShowSoftInputOnce(): Boolean {
        if (!isAttachedToWindow) return false
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        imm.restartInput(this)
        requestFocus()
        val accepted = imm.showSoftInput(this, 0)
        return accepted || imm.isActive(this)
    }

    private fun clearSoftImeShowRetries() {
        for (runnable in softImeShowRetryRunnables) {
            removeCallbacks(runnable)
        }
        softImeShowRetryRunnables.clear()
    }

    /** Hide soft IME + clear host composing. */
    fun hideSoftKeyboard() {
        imeWanted = false
        clearSoftImeShowRetries()
        Log.i(TAG, "IME soft keyboard hide")
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(windowToken, 0)
        updateImeUiState(composingText = "", keyboardVisible = false)
        if (isAttachedToWindow) {
            imm?.restartInput(this)
        }
    }

    fun isSoftKeyboardWanted(): Boolean = imeWanted

    /** Chip / letterbox long-press: flip [imeWanted] then show or hide. */
    fun toggleSoftKeyboard() {
        if (WineAndroidImeUi.toggleImeWanted(imeWanted)) {
            showSoftKeyboard()
        } else {
            hideSoftKeyboard()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            hideSoftKeyboard()
        }
    }

    fun setImeUiStateListener(listener: ((ImeUiState) -> Unit)?) {
        imeUiStateListener = listener
        listener?.invoke(imeUiState)
    }

    private fun updateImeUiState(
        composingText: String = imeUiState.composingText,
        keyboardVisible: Boolean = imeUiState.keyboardVisible,
    ) {
        val updated = WineAndroidImeUi.update(imeUiState, composingText, keyboardVisible)
        if (updated === imeUiState) return
        imeUiState = updated
        imeUiStateListener?.invoke(updated)
    }

    fun attachWindow(window: WineAndroidWindow, onSurface: (hwnd: Int, surface: Surface?) -> Unit) {
        val key = Key(window.hwnd, window.isClient)
        groups.remove(key)?.let {
            detachGroupView(it)
            it.releaseLayer()
        }

        if (window.visibleRect.width() <= 0 && window.windowRect.width() > 0) {
            window.visibleRect = Rect(window.windowRect)
        }
        // Non-desktop windows may start at 0×0 until WINDOW_POS; buffers use min 2×2
        // but first nativeRegisterSurface is deferred until rects have real w×h.

        val group = WindowGroup(context, window, onSurface)
        groups[key] = group
        trackSibling(window)
        layoutGroup(group)
        group.applyFixedBufferSize()
        layerRoot?.let { group.attachLayer(it) }
        applyVisibility(group)
        if (window.visible) syncZOrder(stackKeyFor(window.parentHwnd))
        currentPointerIcon?.let { icon -> group.pointerIcon = icon }
    }

    fun detachWindow(hwnd: Int) {
        if (captureHwnd == hwnd) {
            captureHwnd = 0
            Log.i(TAG, "capture cleared (destroyed hwnd=$hwnd)")
        }
        val parentHwnd = groups.values.firstOrNull { it.window.hwnd == hwnd }?.window?.parentHwnd
        listOf(false, true).forEach { client ->
            groups.remove(Key(hwnd, client))?.let {
                detachGroupView(it)
                it.releaseLayer()
            }
        }
        if (parentHwnd != null) {
            untrackSibling(hwnd, parentHwnd)
        } else {
            untrackSiblingEverywhere(hwnd)
        }
    }

    private fun onLayerRootCreated(root: SurfaceControl) {
        layerRoot = root
        Log.i(TAG, "layer root created; attaching ${groups.size} window layers")
        groups.values.forEach { it.attachLayer(root) }
        syncLayers()
    }

    private fun onLayerRootDestroyed() {
        layerRoot = null
        Log.i(TAG, "layer root destroyed; ${groups.size} window layers offscreen")
        // Same contract as a destroyed SurfaceView: Wine sees the surface go
        // away until the root returns and the layers are reattached.
        groups.values.forEach { it.onLayerRootLost() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Children (and z-order via bringChildToFront) have their final frames now.
        syncLayers()
    }

    /**
     * Push every window layer's stacking, placement and visibility in one
     * transaction. The view tree is the source of truth: z follows its drawing
     * order (WineAndroidWindowStack via bringChildToFront), a window's frame is
     * its view frame, and hidden windows (detached views) get no placement.
     */
    private fun syncLayers() {
        if (layerRoot == null) return
        val roots =
            (0 until contentHost.childCount)
                .map { contentHost.getChildAt(it) }
                .filterIsInstance<WindowGroup>()
                .map { it.layerNode() }
        val placements = WineAndroidLayerGeometry.place(roots)
        val placed = HashSet<WindowGroup>()
        SurfaceControl.Transaction().use { t ->
            for (p in placements) {
                val layer = p.key.layer ?: continue
                placed += p.key
                t.setGeometry(layer, p.source.toRect(), p.destination.toRect(), Surface.ROTATION_0)
                t.setLayer(layer, p.z)
                t.setVisibility(layer, true)
            }
            groups.values.forEach { group ->
                if (group !in placed) group.layer?.let { t.setVisibility(it, false) }
            }
            t.apply()
        }
        val order = placements.map { it.key.layerName }
        if (order != lastLayerOrder) {
            lastLayerOrder = order
            Log.i(TAG, "layers bottom→top ${order.joinToString(",")}")
        }
    }

    private fun WineAndroidLayerGeometry.Box.toRect() = Rect(left, top, right, bottom)

    fun updateWindow(window: WineAndroidWindow) {
        val held = groups[Key(window.hwnd, window.isClient)] ?: return
        held.window = window
        // Parent may have changed via copy — reparent if needed.
        ensureParent(held)
        layoutGroup(held)
        held.applyFixedBufferSize()
        applyVisibility(held)
        held.requestLayout()
    }

    fun updateHwndRects(
        hwnd: Int,
        windowRect: Rect,
        clientRect: Rect,
        visibleRect: Rect,
        style: Int,
        flags: Int = WineAndroidWindowStack.SWP_NOZORDER,
        insertAfter: Int = 0,
    ) {
        val matched = groups.filterKeys { it.hwnd == hwnd }
        if (matched.isEmpty()) return
        val wasVisible = matched.values.first().window.visible
        val nowVisible = WineAndroidWindowStack.isStyleVisible(style)
        matched.forEach { (_, held) ->
            held.window.windowRect = Rect(windowRect)
            held.window.clientRect = Rect(clientRect)
            held.window.visibleRect = Rect(visibleRect)
            held.window.style = style
            held.window.visible = nowVisible
            layoutGroup(held)
            held.applyFixedBufferSize()
            applyVisibility(held)
            held.requestLayout()
        }
        val stackKey = stackKeyFor(matched.values.first().window.parentHwnd)
        when {
            WineAndroidWindowStack.wantsZOrder(flags) -> applyZOrder(hwnd, insertAfter, stackKey)
            nowVisible && !wasVisible -> syncZOrder(stackKey)
        }
    }

    fun reparent(hwnd: Int, newParentHwnd: Int) {
        val matched = groups.filterKeys { it.hwnd == hwnd }
        if (matched.isEmpty()) return
        val oldParent = matched.values.first().window.parentHwnd
        untrackSibling(hwnd, oldParent)
        matched.forEach { (_, held) ->
            held.window.parentHwnd = newParentHwnd
            layoutGroup(held)
            held.applyFixedBufferSize()
            applyVisibility(held)
            held.requestLayout()
        }
        trackSibling(matched.values.first().window)
        if (matched.values.first().window.visible) {
            syncZOrder(stackKeyFor(newParentHwnd))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        recalculateScale("onSizeChanged ${w}x$h")
        relayoutAll()
    }

    private fun recalculateScale(reason: String) {
        val gw = guestDesktopWidth
        val gh = guestDesktopHeight
        val layout = WineAndroidHostScale.compute(gw, gh, width, height)
        if (layout.contentWidth <= 0 || layout.contentHeight <= 0) {
            hostScale = 1f
            offsetX = 0
            offsetY = 0
            contentHost.layoutParams = LayoutParams(0, 0)
            return
        }
        hostScale = layout.scale
        offsetX = layout.offsetX
        offsetY = layout.offsetY
        contentHost.layoutParams =
            LayoutParams(layout.contentWidth, layout.contentHeight).apply {
                leftMargin = offsetX
                topMargin = offsetY
            }
        contentHost.requestLayout()
        Log.i(
            TAG,
            "hostScale-to-fill $reason guest=${gw}x$gh host=${width}x$height " +
                "scale=${layout.scale} offset=$offsetX,$offsetY " +
                "content=${layout.contentWidth}x${layout.contentHeight}",
        )
    }

    private fun relayoutAll() {
        // Top-level first so nested parents have correct size before children.
        val ordered = groups.values.sortedBy { nestingDepth(it.window.parentHwnd) }
        ordered.forEach { held ->
            ensureParent(held)
            layoutGroup(held)
            held.applyFixedBufferSize()
            applyVisibility(held)
            held.requestLayout()
        }
    }

    private fun nestingDepth(parentHwnd: Int): Int {
        if (isTopLevel(parentHwnd)) return 0
        var depth = 0
        var p = parentHwnd
        val seen = HashSet<Int>()
        while (!isTopLevel(p) && seen.add(p)) {
            depth++
            val parentGroup = findParentGroup(p) ?: break
            p = parentGroup.window.parentHwnd
        }
        return depth
    }

    private fun isTopLevel(parentHwnd: Int): Boolean = parentHwnd == 0 || parentHwnd == desktopHwnd

    private fun isDesktopGroup(group: WindowGroup): Boolean = desktopHwnd != 0 && group.window.hwnd == desktopHwnd

    private fun findParentGroup(parentHwnd: Int): WindowGroup? =
        groups[Key(parentHwnd, false)] ?: groups[Key(parentHwnd, true)]

    private fun stackKeyFor(parentHwnd: Int): Int = if (isTopLevel(parentHwnd)) 0 else parentHwnd

    private fun trackSibling(window: WineAndroidWindow) {
        val key = stackKeyFor(window.parentHwnd)
        val stack = siblingStacks.getOrPut(key) { mutableListOf() }
        val updated = WineAndroidWindowStack.ensureTracked(stack, window.hwnd)
        if (updated != stack) {
            stack.clear()
            stack.addAll(updated)
        }
    }

    private fun untrackSibling(hwnd: Int, parentHwnd: Int) {
        val key = stackKeyFor(parentHwnd)
        val stack = siblingStacks[key] ?: return
        val updated = WineAndroidWindowStack.remove(stack, hwnd)
        stack.clear()
        stack.addAll(updated)
        if (stack.isEmpty()) siblingStacks.remove(key)
    }

    private fun untrackSiblingEverywhere(hwnd: Int) {
        val keys = siblingStacks.keys.toList()
        for (key in keys) {
            val stack = siblingStacks[key] ?: continue
            if (!stack.contains(hwnd)) continue
            val updated = WineAndroidWindowStack.remove(stack, hwnd)
            stack.clear()
            stack.addAll(updated)
            if (stack.isEmpty()) siblingStacks.remove(key)
        }
    }

    private fun addGroupToParent(group: WindowGroup) {
        val parentHwnd = group.window.parentHwnd
        val parentGroup =
            if (isTopLevel(parentHwnd)) {
                null
            } else {
                findParentGroup(parentHwnd)
            }
        val host: FrameLayout = parentGroup ?: contentHost
        if (group.parent !== host) {
            (group.parent as? FrameLayout)?.removeView(group)
            if (isDesktopGroup(group) && host === contentHost) {
                // The desktop is the bottom of the stack: right above [layerHost].
                host.addView(group, 1)
            } else {
                host.addView(group)
            }
        }
    }

    private fun ensureParent(group: WindowGroup) {
        if (group.window.visible) {
            addGroupToParent(group)
        } else {
            detachGroupView(group)
        }
    }

    private fun detachGroupView(group: WindowGroup) {
        (group.parent as? FrameLayout)?.removeView(group)
    }

    private fun layoutGroup(group: WindowGroup) {
        val window = group.window
        val isDesktop = window.hwnd == desktopHwnd && desktopHwnd != 0
        val coverRect = clientAreaRect(window) ?: window.visibleRect
        if (isDesktop || (
                isTopLevel(window.parentHwnd) && guestDesktopWidth > 0 &&
                    coverRect.width() >= guestDesktopWidth &&
                    coverRect.height() >= guestDesktopHeight
                )
        ) {
            // Desktop hwnd fills contentHost.
            group.layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    leftMargin = 0
                    topMargin = 0
                }
            return
        }

        val r = layoutRect(window)
        val scale = hostScale
        // Upstream set_layout: never leave empty views (min ~2 guest px).
        val guestW = max(MIN_GUEST_PX, r.width())
        val guestH = max(MIN_GUEST_PX, r.height())
        val width = max(1, (guestW * scale).toInt())
        val height = max(1, (guestH * scale).toInt())
        val left = (r.left * scale).toInt()
        val top = (r.top * scale).toInt()
        group.layoutParams =
            LayoutParams(width, height).apply {
                leftMargin = left
                topMargin = top
            }
    }

    private fun layoutRect(window: WineAndroidWindow): Rect {
        clientAreaRect(window)?.let { return it }
        val v = window.visibleRect
        if (v.width() > 0 || v.height() > 0) return v
        return window.windowRect
    }

    /**
     * A client (Vulkan/GL) view covers only the client area, like upstream's
     * client_group inside window_group; the frame and caption stay on the GDI
     * view below it. clientRect is in the same parent-client space as
     * visibleRect, so the client group can sit next to the GDI group. Sizing it
     * to the window rect made the swapchain image (client size) smaller than
     * the Surface buffers and drew it over the caption.
     */
    private fun clientAreaRect(window: WineAndroidWindow): Rect? =
        window.clientRect.takeIf { window.isClient && it.width() > 0 && it.height() > 0 }

    /**
     * Upstream WineWindow.set_zorder + sync_views_zorder: update sibling stack,
     * then bringChildToFront visible groups bottom→top.
     */
    private fun applyZOrder(hwnd: Int, insertAfter: Int, stackKey: Int, reason: String = "apply") {
        val stack = siblingStacks.getOrPut(stackKey) { mutableListOf() }
        val updated = WineAndroidWindowStack.reorder(stack, hwnd, insertAfter)
        stack.clear()
        stack.addAll(updated)
        syncZOrder(stackKey, reason = reason)
    }

    private fun syncZOrder(stackKey: Int, reason: String = "sync") {
        val stack = siblingStacks[stackKey] ?: return
        val bringOrder =
            WineAndroidWindowStack.syncBringToFrontOrder(stack) { hwnd ->
                isHwndVisibleInStack(hwnd)
            }
        if (
            WineAndroidDebugZOrderInject.hasOverlapCandidates(stack) { hwnd ->
                isHwndVisibleInStack(hwnd)
            }
        ) {
            Log.i(
                TAG,
                WineAndroidDebugZOrderInject.formatSyncLine(
                    parentKey = stackKey,
                    topFirst = stack.toList(),
                    bringOrder = bringOrder,
                    reason = reason,
                ),
            )
        }
        var touchedParent: FrameLayout? = null
        for (hwnd in bringOrder) {
            // GDI first, then the client view so the swapchain layer sits above
            // its own frame (the GDI buffer covers the whole window rect).
            listOf(false, true).forEach { client ->
                val group = groups[Key(hwnd, client)] ?: return@forEach
                // The desktop is not in the top-level stack; bringing it to the
                // front (its own parent key syncs too) would cover every window.
                if (isDesktopGroup(group)) return@forEach
                val parent = group.parent as? FrameLayout ?: return@forEach
                parent.bringChildToFront(group)
                touchedParent = parent
            }
        }
        touchedParent?.requestLayout()
    }

    /**
     * Upstream add_view_to_parent / remove_view_from_parent driven by WS_VISIBLE.
     * Invisible groups leave the parent entirely (not merely GONE) so layer
     * stacking and hit-testing match sibling order.
     */
    private fun applyVisibility(group: WindowGroup) {
        if (group.window.visible) {
            addGroupToParent(group)
            group.visibility = View.VISIBLE
        } else {
            detachGroupView(group)
        }
    }

    private inner class WindowGroup(
        context: Context,
        var window: WineAndroidWindow,
        private val onSurface: (hwnd: Int, surface: Surface?) -> Unit,
    ) : FrameLayout(context) {
        private var lastBufferW: Int = -1
        private var lastBufferH: Int = -1

        /** True after the first non-deferred nativeRegisterSurface for this surface. */
        private var firstRegisterDone: Boolean = false

        /**
         * This HWND's buffer layer, a child of [layerRoot]. The view itself only
         * lays out, hit-tests and parents child windows; it never draws.
         */
        var layer: SurfaceControl? = null
            private set
        private var layerSurface: Surface? = null

        /** "gdi-10054" / "client-10054": SurfaceFlinger name suffix and log label. */
        val layerName: String get() = (if (window.isClient) "client-" else "gdi-") + window.hwnd.toString(16)

        init {
            isClickable = true
            // Upstream WineView.setFocusable(!client): GDI group takes keys.
            isFocusable = !window.isClient
            isFocusableInTouchMode = !window.isClient
            // GDI groups take focus on tap for hardware keys; not a text editor
            // (default onCheckIsTextEditor=false) so focus alone does not open IME.
            setWillNotDraw(true)
        }

        /**
         * Create the layer under [root], or move the existing one there when the
         * root SurfaceView came back. Either way Wine gets the surface again.
         */
        fun attachLayer(root: SurfaceControl) {
            val existing = layer
            if (existing == null) {
                // Client views carry Vulkan/GL swapchain images. Windows ignores their
                // alpha, so the layer is opaque; the buffer format still comes from the
                // swapchain (SET_BUFFERS_FORMAT). GDI layers stay RGBA_8888 (docs/04).
                val created =
                    SurfaceControl.Builder()
                        .setName("amphora-$layerName")
                        .setParent(root)
                        .setBufferSize(max(MIN_GUEST_PX, lastBufferW), max(MIN_GUEST_PX, lastBufferH))
                        .setFormat(PixelFormat.RGBA_8888)
                        .setOpaque(window.isClient)
                        .build()
                layer = created
                layerSurface = Surface(created)
            } else {
                SurfaceControl.Transaction().use { it.reparent(existing, root).apply() }
            }
            val surface = layerSurface ?: return
            window.surface = surface
            tryEmitSurface(surface, if (existing == null) "layerCreated" else "layerReattached")
        }

        /** The root SurfaceView is gone: Wine loses the surface, the layer is kept for reattach. */
        fun onLayerRootLost() {
            firstRegisterDone = false
            window.surface = null
            onSurface(window.hwnd, null)
        }

        fun releaseLayer() {
            val held = layer ?: return
            layer = null
            window.surface = null
            SurfaceControl.Transaction().use { it.reparent(held, null).apply() }
            layerSurface?.release()
            layerSurface = null
            held.release()
        }

        /** This view's subtree for [WineAndroidLayerGeometry]; frames are parent-relative. */
        fun layerNode(): WineAndroidLayerGeometry.Node<WindowGroup> = WineAndroidLayerGeometry.Node(
            key = this,
            frame = WineAndroidLayerGeometry.Box(left, top, right, bottom),
            bufferWidth = if (layer != null && firstRegisterDone) lastBufferW else 0,
            bufferHeight = if (layer != null && firstRegisterDone) lastBufferH else 0,
            children =
            (0 until childCount)
                .map { getChildAt(it) }
                .filterIsInstance<WindowGroup>()
                .map { it.layerNode() },
        )

        /**
         * Map a view-local event to guest desktop px (Wine ABSOLUTE coords).
         * Walk view offsets up to [contentHost], then divide by [hostScale].
         */
        private fun guestDesktopPos(event: MotionEvent): Pair<Int, Int> {
            var x = event.x
            var y = event.y
            var v: View = this
            while (v !== contentHost) {
                x += v.left
                y += v.top
                val parent = v.parent as? View ?: break
                if (parent === contentHost) break
                v = parent
            }
            val scale = if (hostScale > 0f) hostScale else 1f
            return (x / scale).roundToInt() to (y / scale).roundToInt()
        }

        // Every touch goes to Wine as MOTION_EVENT; there is no host click action.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val targetHwnd =
                WineAndroidCaptureTarget.resolve(captureHwnd, window.hwnd)
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !window.isClient) {
                keyTargetHwnd = targetHwnd
                requestFocus()
                // Soft IME lives on the desktop root (InputConnection), not the group.
                // Do not auto-show on every tap — explicit [showSoftKeyboard] / IMM only.
                if (WineAndroidImeUi.shouldAutoShowSoftKeyboardOnTouch()) {
                    this@WineAndroidDesktop.showSoftKeyboard()
                }
            }
            val (gx, gy) = guestDesktopPos(event)
            val ok =
                WineAndroidNative.nativeSendMotionEvent(
                    targetHwnd,
                    event.action,
                    gx,
                    gy,
                    event.buttonState,
                    0,
                )
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP
            ) {
                Log.i(
                    TAG,
                    "motion hwnd=$targetHwnd action=${event.actionMasked} " +
                        "guest=$gx,$gy buttons=${event.buttonState} " +
                        "capture=$captureHwnd hit=${window.hwnd} ok=$ok",
                )
            }
            return true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            // Mouse hover / wheel (upstream WineView.onGenericMotionEvent).
            if (event.actionMasked != MotionEvent.ACTION_SCROLL &&
                event.actionMasked != MotionEvent.ACTION_HOVER_MOVE &&
                event.actionMasked != MotionEvent.ACTION_BUTTON_PRESS &&
                event.actionMasked != MotionEvent.ACTION_BUTTON_RELEASE
            ) {
                return super.onGenericMotionEvent(event)
            }
            val targetHwnd =
                WineAndroidCaptureTarget.resolve(captureHwnd, window.hwnd)
            val (gx, gy) = guestDesktopPos(event)
            val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL).roundToInt()
            return WineAndroidNative.nativeSendMotionEvent(
                targetHwnd,
                event.action,
                gx,
                gy,
                event.buttonState,
                vscroll,
            )
        }

        override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? =
            currentPointerIcon ?: super.onResolvePointerIcon(event, pointerIndex)

        /**
         * Keep the layer's buffer at guest px (view layout is host-scaled), and
         * re-register after a size change so native sends SURFACE_CHANGED.
         */
        fun applyFixedBufferSize() {
            val r = bufferRect(window)
            val realW = r.width()
            val realH = r.height()
            val bw = max(MIN_GUEST_PX, if (realW > 0) realW else MIN_GUEST_PX)
            val bh = max(MIN_GUEST_PX, if (realH > 0) realH else MIN_GUEST_PX)
            val changed = bw != lastBufferW || bh != lastBufferH
            if (changed || lastBufferW < 0) {
                layer?.let { held -> SurfaceControl.Transaction().use { it.setBufferSize(held, bw, bh).apply() } }
                Log.i(
                    TAG,
                    "buffer size hwnd=${window.hwnd} ${bw}x$bh " +
                        "(was ${lastBufferW}x$lastBufferH) real=${realW}x$realH " +
                        "visible=${window.visibleRect} window=${window.windowRect}",
                )
                lastBufferW = bw
                lastBufferH = bh
            }
            val surface = layerSurface ?: return
            if (!surface.isValid) return
            // First register once real guest dims are known (even if forceReregister
            // is false — e.g. attach after sibling copy / desktop create size).
            // After that, re-bind whenever the buffer size moved.
            if (!firstRegisterDone) {
                tryEmitSurface(surface, "applyFixedBufferSize")
            } else if (changed) {
                tryEmitSurface(surface, "applyFixedBufferSize-reregister")
            }
        }

        /**
         * Guest buffer size from WINDOW_POS / create rects — **before** MIN 2×2 clamp.
         * Placeholder 0/0 (or one zero edge) must not count as real.
         */
        private fun hasRealGuestSize(): Boolean {
            val r = bufferRect(window)
            return r.width() > 0 && r.height() > 0
        }

        /**
         * Gate [onSurface] → nativeRegisterSurface:
         * - first call waits for [hasRealGuestSize]; artificial MIN 2×2 alone is not enough.
         * - after first success, always re-bind (buffer size change / reattach).
         */
        private fun tryEmitSurface(surface: Surface, reason: String) {
            if (!surface.isValid || layerRoot == null) return
            if (!firstRegisterDone) {
                if (!hasRealGuestSize()) {
                    val r = bufferRect(window)
                    Log.i(
                        TAG,
                        "defer first register hwnd=${window.hwnd} reason=$reason " +
                            "rect=${r.width()}x${r.height()} (placeholder / awaiting WINDOW_POS)",
                    )
                    return
                }
                firstRegisterDone = true
                val r = bufferRect(window)
                Log.i(
                    TAG,
                    "first register hwnd=${window.hwnd} reason=$reason " +
                        "guest=${r.width()}x${r.height()}",
                )
                onSurface(window.hwnd, surface)
                // Now that it has a buffer size worth showing, give it a placement.
                syncLayers()
                return
            }
            onSurface(window.hwnd, surface)
        }

        private fun bufferRect(window: WineAndroidWindow): Rect {
            clientAreaRect(window)?.let { return it }
            val v = window.visibleRect
            if (v.width() > 0 && v.height() > 0) return v
            return window.windowRect
        }
    }

    private companion object {
        const val TAG = "WineAndroidDesktop"
        const val MIN_GUEST_PX = 2
    }
}
