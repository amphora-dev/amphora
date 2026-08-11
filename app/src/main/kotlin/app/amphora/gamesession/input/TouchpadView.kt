package app.amphora.gamesession.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toDrawable
import com.winlator.cmod.runtime.display.renderer.ViewTransformation
import com.winlator.cmod.runtime.display.xserver.Pointer
import com.winlator.cmod.runtime.display.xserver.XServer
import com.winlator.cmod.shared.math.Mathf
import com.winlator.cmod.shared.math.XForm

/**
 * Amphora port of WinNative [com.winlator.cmod.runtime.input.ui.TouchpadView].
 *
 * Differences from WinNative:
 * - No WinHandler / relative-UDP mouse path — all buttons and motion go through X inject
 *   (`injectPointerMove` / `injectPointerMoveDelta` / button press-release).
 * - No RTS gestures / ScreenTouchStick / InputControls coupling.
 * - Trackpad (default) + touchscreen absolute modes only.
 * - Hardware keyboard events are forwarded directly through [XServer.keyboard].
 * - Pointer inject runs on a dedicated thread with motion coalescing: synchronous
 *   `ClientSocket.write` on the UI thread ANRs when Wine is slow to drain the X socket
 *   (seen as "Input dispatching timed out" on MainActivity).
 *
 * Constructed only from Compose [AndroidView] with an [XServer]; XML inflation is unused
 * (same pattern as [com.winlator.cmod.runtime.display.ui.XServerSurfaceView]).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TouchpadView(context: Context, private val xServer: XServer) : View(context) {
    companion object {
        const val CURSOR_ACCELERATION = 1.25f
        const val CURSOR_ACCELERATION_THRESHOLD: Byte = 6
        private const val MAX_FINGERS: Byte = 4
        const val MAX_TAP_MILLISECONDS: Short = 200
        const val MAX_TAP_TRAVEL_DISTANCE: Byte = 10
        private const val MAX_TWO_FINGERS_SCROLL_DISTANCE: Short = 350
        private const val LONG_PRESS_RIGHT_CLICK_MS = 1000L
        private const val CLICK_DELAYED_TIME = 50L
        private const val EFFECTIVE_TOUCH_DISTANCE = 20
        const val MODE_TRACKPAD = 0
        const val MODE_TOUCHSCREEN = 1
        private const val TOUCHSCREEN_DOUBLE_TAP_MS = 500L
        private const val TOUCHSCREEN_DOUBLE_TAP_DISTANCE = 100f
    }

    private val injectThread =
        HandlerThread("TouchpadXInject").also { it.start() }
    private val injectHandler = Handler(injectThread.looper)
    private var inputReleased = false
    private val moveLock = Any()
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingAbsX: Int? = null
    private var pendingAbsY: Int? = null
    private var movePosted = false

    private var continueClick = true
    private var lastTapDownTime = 0L
    private var lastTapRawX = 0f
    private var lastTapRawY = 0f
    private var lastTapTransX = 0
    private var lastTapTransY = 0
    private var fingerPointerButtonLeft: Finger? = null
    private var fingerPointerButtonRight: Finger? = null
    private val fingers = arrayOfNulls<Finger>(4)
    private var fourFingersTapCallback: Runnable? = null
    private var lastTouchedPosX = 0
    private var lastTouchedPosY = 0
    private var mouseEnabled = true
    private var numFingers: Byte = 0
    private var pointerButtonLeftEnabled = true
    private var pointerButtonRightEnabled = true
    private var resolutionScale = 0f
    private var scrollAccumY = 0f
    private var scrolling = false
    private var sensitivity = 1.0f
    private var simTouchScreen = false
    private var screenTouchMode = MODE_TRACKPAD
    private val xform = XForm.getInstance()
    private var activeTouchHandler: ((MotionEvent) -> Boolean)? = null
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressActive = false
    private val longPressRunnable = Runnable {
        if (tapToClickEnabled &&
            numFingers.toInt() == 1 &&
            fingers[0] != null &&
            fingers[0]!!.travelDistance() < MAX_TAP_TRAVEL_DISTANCE
        ) {
            longPressActive = true
            runInject {
                if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                }
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
            }
        }
    }
    private val pointerIdsToIgnore = mutableSetOf<Int>()

    var tapToClickEnabled = true
        set(value) {
            field = value
            if (!value) resetInputState()
        }

    /** Flush coalesced motion, then run [block] on the inject thread (ordered vs moves). */
    private fun runInject(block: () -> Unit) {
        if (inputReleased) return
        injectHandler.post {
            drainPendingMove()
            block()
        }
    }

    private fun queueMoveDelta(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        synchronized(moveLock) {
            pendingAbsX = null
            pendingAbsY = null
            pendingDx += dx
            pendingDy += dy
            scheduleMoveLocked()
        }
    }

    private fun queueMoveAbs(x: Int, y: Int) {
        synchronized(moveLock) {
            pendingDx = 0
            pendingDy = 0
            pendingAbsX = x
            pendingAbsY = y
            scheduleMoveLocked()
        }
    }

    private fun scheduleMoveLocked() {
        if (movePosted) return
        movePosted = true
        injectHandler.post { drainPendingMove() }
    }

    private fun drainPendingMove() {
        val absX: Int?
        val absY: Int?
        val dx: Int
        val dy: Int
        synchronized(moveLock) {
            absX = pendingAbsX
            absY = pendingAbsY
            dx = pendingDx
            dy = pendingDy
            pendingAbsX = null
            pendingAbsY = null
            pendingDx = 0
            pendingDy = 0
            movePosted = false
        }
        when {
            absX != null && absY != null -> xServer.injectPointerMove(absX, absY)
            dx != 0 || dy != 0 -> xServer.injectPointerMoveDelta(dx, dy)
        }
    }

    private fun pressButton(button: Pointer.Button) {
        runInject { xServer.injectPointerButtonPress(button) }
    }

    private fun releaseButton(button: Pointer.Button) {
        runInject { xServer.injectPointerButtonRelease(button) }
    }

    private fun clickButton(button: Pointer.Button) {
        runInject {
            xServer.injectPointerButtonPress(button)
            xServer.injectPointerButtonRelease(button)
        }
    }

    init {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        background = createTransparentBg()
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        // Hide the Android host pointer so only the Wine/Vulkan cursor shows.
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        val metrics = resources.displayMetrics
        updateXform(
            metrics.widthPixels,
            metrics.heightPixels,
            xServer.screenInfo.width.toInt(),
            xServer.screenInfo.height.toInt(),
        )
        setOnGenericMotionListener { _, event ->
            when {
                !mouseEnabled -> true
                event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS -> handleStylusHoverEvent(event)
                event.isFromSource(InputDevice.SOURCE_MOUSE) -> onExternalMouseEvent(event)
                else -> false
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateXform(w, h, xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
        resolutionScale = 1000.0f / Math.min(xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        xServer.keyboard.onKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        xServer.keyboard.onKeyEvent(event) || super.onKeyUp(keyCode, event)

    override fun onDetachedFromWindow() {
        releaseInput()
        super.onDetachedFromWindow()
    }

    fun releaseInput() {
        if (inputReleased) return
        longPressHandler.removeCallbacks(longPressRunnable)
        injectHandler.removeCallbacksAndMessages(null)
        resetInputState()
        inputReleased = true
        injectThread.quitSafely()
    }

    private fun updateXform(outerWidth: Int, outerHeight: Int, innerWidth: Int, innerHeight: Int) {
        if (outerWidth <= 0 || outerHeight <= 0 || innerWidth <= 0 || innerHeight <= 0) return
        val viewTransformation = ViewTransformation()
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight)
        val invAspect = 1.0f / viewTransformation.aspect
        val renderer = xServer.renderer
        if (renderer != null && !renderer.isFullscreen) {
            XForm.makeTranslation(
                xform,
                -viewTransformation.viewOffsetX.toFloat(),
                -viewTransformation.viewOffsetY.toFloat(),
            )
            XForm.scale(xform, invAspect, invAspect)
        } else {
            XForm.makeScale(
                xform,
                innerWidth.toFloat() / outerWidth.toFloat(),
                innerHeight.toFloat() / outerHeight.toFloat(),
            )
        }
    }

    private inner class Finger(x: Float, y: Float) {
        var lastX: Int
        var lastY: Int
        val startX: Int
        val startY: Int
        val touchTime: Long = System.currentTimeMillis()
        var x: Int
        var y: Int

        init {
            val transformedPoint = XForm.transformPoint(xform, x, y)
            val ix = transformedPoint[0].toInt()
            this.x = ix
            this.lastX = ix
            this.startX = ix
            val iy = transformedPoint[1].toInt()
            this.y = iy
            this.lastY = iy
            this.startY = iy
        }

        fun update(x: Float, y: Float) {
            this.lastX = this.x
            this.lastY = this.y
            val transformedPoint = XForm.transformPoint(xform, x, y)
            this.x = transformedPoint[0].toInt()
            this.y = transformedPoint[1].toInt()
        }

        fun deltaX(): Int {
            var dx = (this.x - this.lastX) * sensitivity
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION
            return Mathf.roundPoint(dx)
        }

        fun deltaY(): Int {
            var dy = (this.y - this.lastY) * sensitivity
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION
            return Mathf.roundPoint(dy)
        }

        fun isTap(): Boolean = System.currentTimeMillis() - touchTime < MAX_TAP_MILLISECONDS &&
            travelDistance() < MAX_TAP_TRAVEL_DISTANCE

        fun travelDistance(): Float = Math.hypot((x - startX).toDouble(), (y - startY).toDouble()).toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!mouseEnabled) return true
        showCursor()
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return handleStylusEvent(event)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || activeTouchHandler == null) {
            activeTouchHandler = selectTouchHandler()
        }
        val result = activeTouchHandler!!(event)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeTouchHandler = null
        }
        return result
    }

    private fun selectTouchHandler(): (MotionEvent) -> Boolean = when (screenTouchMode) {
        MODE_TOUCHSCREEN -> ::handleTouchscreenEvent
        else -> ::handleTouchpadEvent
    }

    private fun showCursor() {
        xServer.renderer?.setCursorVisible(true)
    }

    private fun handleStylusHoverEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_HOVER_MOVE) {
            val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
            queueMoveAbs(transformedPoint[0].toInt(), transformedPoint[1].toInt())
            return true
        }
        return false
    }

    private fun handleStylusEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleStylusLeftClick(event)
            MotionEvent.ACTION_MOVE -> handleStylusMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleStylusUp()
            else -> {
                // Secondary button (stylus barrel) → right click.
                if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                    handleStylusRightClick(event)
                }
            }
        }
        return true
    }

    private fun handleStylusLeftClick(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        queueMoveAbs(transformedPoint[0].toInt(), transformedPoint[1].toInt())
        pressButton(Pointer.Button.BUTTON_LEFT)
    }

    private fun handleStylusRightClick(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        queueMoveAbs(transformedPoint[0].toInt(), transformedPoint[1].toInt())
        pressButton(Pointer.Button.BUTTON_RIGHT)
    }

    private fun handleStylusMove(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        queueMoveAbs(transformedPoint[0].toInt(), transformedPoint[1].toInt())
    }

    private fun handleStylusUp() {
        releaseButton(Pointer.Button.BUTTON_LEFT)
        releaseButton(Pointer.Button.BUTTON_RIGHT)
    }

    private fun handleTouchpadEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val actionMasked = event.actionMasked
        if (actionMasked != MotionEvent.ACTION_MOVE &&
            (pointerId >= MAX_FINGERS || pointerIdsToIgnore.contains(pointerId))
        ) {
            return true
        }
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
                scrollAccumY = 0.0f
                scrolling = false
                fingers[pointerId] = Finger(event.getX(actionIndex), event.getY(actionIndex))
                numFingers = (numFingers + 1).toByte()
                if (numFingers.toInt() > 1) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (pointerId == 0 && numFingers.toInt() == 1 && !simTouchScreen) {
                    longPressActive = false
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_RIGHT_CLICK_MS)
                } else {
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (simTouchScreen && tapToClickEnabled) {
                    val clickDelay = Runnable {
                        if (continueClick) {
                            queueMoveAbs(lastTouchedPosX, lastTouchedPosY)
                            pressButton(Pointer.Button.BUTTON_LEFT)
                        }
                    }
                    if (pointerId == 0) {
                        continueClick = true
                        if (Math.hypot(
                                (fingers[0]!!.x - lastTouchedPosX).toDouble(),
                                (fingers[0]!!.y - lastTouchedPosY).toDouble(),
                            ) * resolutionScale > EFFECTIVE_TOUCH_DISTANCE
                        ) {
                            lastTouchedPosX = fingers[0]!!.x
                            lastTouchedPosY = fingers[0]!!.y
                        }
                        postDelayed(clickDelay, CLICK_DELAYED_TIME)
                    } else if (pointerId == 1) {
                        if (numFingers < 2) {
                            continueClick = true
                            if (Math.hypot(
                                    (fingers[1]!!.x - lastTouchedPosX).toDouble(),
                                    (fingers[1]!!.y - lastTouchedPosY).toDouble(),
                                ) * resolutionScale > EFFECTIVE_TOUCH_DISTANCE
                            ) {
                                lastTouchedPosX = fingers[1]!!.x
                                lastTouchedPosY = fingers[1]!!.y
                            }
                            postDelayed(clickDelay, CLICK_DELAYED_TIME)
                        } else {
                            continueClick =
                                System.currentTimeMillis() - fingers[0]!!.touchTime > CLICK_DELAYED_TIME
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (fingers[pointerId] != null) {
                    fingers[pointerId]!!.update(event.getX(actionIndex), event.getY(actionIndex))
                    if (!longPressActive) handleFingerUp(fingers[pointerId]!!)
                    longPressActive = false
                    fingers[pointerId] = null
                    numFingers = (numFingers - 1).toByte()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
                    queueMoveAbs(transformedPoint[0].toInt(), transformedPoint[1].toInt())
                } else {
                    for (i in 0 until 4) {
                        if (fingers[i] != null) {
                            if (pointerIdsToIgnore.contains(i)) {
                                fingers[i] = null
                                numFingers = (numFingers - 1).toByte()
                                continue
                            }
                            val pointerIndex = event.findPointerIndex(i)
                            if (pointerIndex >= 0) {
                                fingers[i]!!.update(event.getX(pointerIndex), event.getY(pointerIndex))
                                handleFingerMove(fingers[i]!!)
                            } else {
                                handleFingerUp(fingers[i]!!)
                                fingers[i] = null
                                numFingers = (numFingers - 1).toByte()
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressActive = false
                for (i in 0 until 4) fingers[i] = null
                numFingers = 0
            }
        }
        return true
    }

    private fun handleTouchscreenEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val ignorePointerId = event.getPointerId(event.actionIndex)
        if (action != MotionEvent.ACTION_MOVE &&
            (ignorePointerId >= MAX_FINGERS || pointerIdsToIgnore.contains(ignorePointerId))
        ) {
            return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Track fingers for two-finger scroll/tap distance checks.
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId < MAX_FINGERS) {
                    fingers[pointerId] = Finger(event.getX(event.actionIndex), event.getY(event.actionIndex))
                    numFingers = (numFingers + 1).toByte()
                }
                handleTouchDown(event)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) handleTwoFingerTap(event) else handleTouchUp(event)
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId < MAX_FINGERS && fingers[pointerId] != null) {
                    fingers[pointerId] = null
                    numFingers = (numFingers - 1).toByte().coerceAtLeast(0).toByte()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2) {
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        if (id < MAX_FINGERS && fingers[id] != null) {
                            fingers[id]!!.update(event.getX(i), event.getY(i))
                        }
                    }
                    handleTwoFingerScroll(event)
                } else {
                    handleTouchMove(event)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseButton(Pointer.Button.BUTTON_LEFT)
                releaseButton(Pointer.Button.BUTTON_RIGHT)
                for (i in 0 until 4) fingers[i] = null
                numFingers = 0
                return true
            }
        }
        return true
    }

    private fun handleTouchDown(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        var tx = transformedPoint[0].toInt()
        var ty = transformedPoint[1].toInt()
        if (event.pointerCount == 1) {
            val now = System.currentTimeMillis()
            val near =
                Math.hypot(
                    (event.x - lastTapRawX).toDouble(),
                    (event.y - lastTapRawY).toDouble(),
                ) < TOUCHSCREEN_DOUBLE_TAP_DISTANCE
            if (now - lastTapDownTime < TOUCHSCREEN_DOUBLE_TAP_MS && near) {
                tx = lastTapTransX
                ty = lastTapTransY
            }
            lastTapDownTime = now
            lastTapRawX = event.x
            lastTapRawY = event.y
            lastTapTransX = tx
            lastTapTransY = ty
        }
        queueMoveAbs(tx, ty)
        if (event.pointerCount == 1 && tapToClickEnabled) {
            pressButton(Pointer.Button.BUTTON_LEFT)
        }
    }

    private fun handleTouchMove(event: MotionEvent) {
        val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
        queueMoveAbs(transformedPoint[0].toInt(), transformedPoint[1].toInt())
    }

    private fun handleTouchUp(@Suppress("UNUSED_PARAMETER") event: MotionEvent) {
        releaseButton(Pointer.Button.BUTTON_LEFT)
    }

    private fun handleTwoFingerScroll(event: MotionEvent) {
        val activeFingers = fingers.filterNotNull()
        if (activeFingers.size < 2) return
        val finger1 = activeFingers[0]
        val finger2 = activeFingers[1]
        val scrollDistance = finger1.y - finger2.y
        if (Math.abs(scrollDistance) > 10) {
            val button =
                if (scrollDistance > 0) {
                    Pointer.Button.BUTTON_SCROLL_UP
                } else {
                    Pointer.Button.BUTTON_SCROLL_DOWN
                }
            clickButton(button)
        }
    }

    private fun handleTwoFingerTap(event: MotionEvent) {
        if (event.pointerCount == 2 && tapToClickEnabled) {
            runInject {
                if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                }
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
            }
        }
    }

    private fun handleFingerUp(finger1: Finger) {
        if (tapToClickEnabled) {
            when (numFingers.toInt()) {
                1 -> {
                    if (simTouchScreen) {
                        injectHandler.postDelayed(
                            {
                                if (continueClick) {
                                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                                }
                            },
                            CLICK_DELAYED_TIME,
                        )
                    } else if (finger1.isTap()) {
                        pressPointerButtonLeft(finger1)
                    }
                }
                2 -> {
                    val finger2 = findSecondFinger(finger1)
                    if (finger2 != null && finger1.isTap()) pressPointerButtonRight(finger1)
                }
                4 -> {
                    fourFingersTapCallback?.let { callback ->
                        for (i in 0 until 4) {
                            if (fingers[i] != null && !fingers[i]!!.isTap()) return
                        }
                        callback.run()
                    }
                }
            }
        }
        releasePointerButtonLeft(finger1)
        releasePointerButtonRight(finger1)
    }

    private fun handleFingerMove(finger1: Finger) {
        if (finger1.travelDistance() >= MAX_TAP_TRAVEL_DISTANCE) {
            longPressHandler.removeCallbacks(longPressRunnable)
        }
        var skipPointerMove = false
        val finger2 = if (numFingers.toInt() == 2) findSecondFinger(finger1) else null
        if (finger2 != null) {
            val resScale =
                1000.0f / Math.min(xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
            val currDistance =
                Math.hypot(
                    (finger1.x - finger2.x).toDouble(),
                    (finger1.y - finger2.y).toDouble(),
                ).toFloat() * resScale
            if (currDistance < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                scrollAccumY +=
                    (finger1.y + finger2.y) * 0.5f - (finger1.lastY + finger2.lastY) * 0.5f
                if (scrollAccumY < -100.0f) {
                    clickButton(Pointer.Button.BUTTON_SCROLL_DOWN)
                    scrollAccumY = 0.0f
                } else if (scrollAccumY > 100.0f) {
                    clickButton(Pointer.Button.BUTTON_SCROLL_UP)
                    scrollAccumY = 0.0f
                }
                scrolling = true
            } else if (
                tapToClickEnabled &&
                currDistance >= MAX_TWO_FINGERS_SCROLL_DISTANCE &&
                fingerPointerButtonLeft == null &&
                finger2.travelDistance() < MAX_TAP_TRAVEL_DISTANCE
            ) {
                pressPointerButtonLeft(finger1)
                skipPointerMove = true
            }
        }
        if (!scrolling && numFingers <= 2 && !skipPointerMove) {
            val drivingFinger =
                if (
                    finger2 != null &&
                    (finger2.deltaX() != 0 || finger2.deltaY() != 0) &&
                    finger1.deltaX() == 0 &&
                    finger1.deltaY() == 0
                ) {
                    finger2
                } else {
                    finger1
                }
            val dx = drivingFinger.deltaX()
            val dy = drivingFinger.deltaY()
            if (simTouchScreen) {
                if (System.currentTimeMillis() - finger1.touchTime > CLICK_DELAYED_TIME) {
                    queueMoveAbs(finger1.x, finger1.y)
                }
            } else {
                queueMoveDelta(dx, dy)
            }
        }
    }

    private fun findSecondFinger(finger: Finger): Finger? {
        for (i in 0 until 4) {
            if (fingers[i] != null && fingers[i] != finger) return fingers[i]
        }
        return null
    }

    private fun pressPointerButtonLeft(finger: Finger) {
        if (!pointerButtonLeftEnabled || fingerPointerButtonLeft != null) return
        fingerPointerButtonLeft = finger
        pressButton(Pointer.Button.BUTTON_LEFT)
    }

    private fun pressPointerButtonRight(finger: Finger) {
        if (!pointerButtonRightEnabled || fingerPointerButtonRight != null) return
        fingerPointerButtonRight = finger
        pressButton(Pointer.Button.BUTTON_RIGHT)
    }

    private fun releasePointerButtonLeft(finger: Finger) {
        if (!pointerButtonLeftEnabled || finger != fingerPointerButtonLeft) return
        injectHandler.postDelayed(
            {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                fingerPointerButtonLeft = null
            },
            30L,
        )
    }

    private fun releasePointerButtonRight(finger: Finger) {
        if (!pointerButtonRightEnabled || finger != fingerPointerButtonRight) return
        injectHandler.postDelayed(
            {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
                fingerPointerButtonRight = null
            },
            30L,
        )
    }

    fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity
    }

    fun setPointerButtonLeftEnabled(enabled: Boolean) {
        this.pointerButtonLeftEnabled = enabled
    }

    fun setPointerButtonRightEnabled(enabled: Boolean) {
        this.pointerButtonRightEnabled = enabled
    }

    fun setFourFingersTapCallback(callback: Runnable?) {
        this.fourFingersTapCallback = callback
    }

    /** Hardware mouse / trackball — absolute move, buttons, scroll wheel. */
    fun onExternalMouseEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        if (!mouseEnabled) return true
        showCursor()
        val actionButton = event.actionButton
        when (event.action) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val transformedPoint = XForm.transformPoint(xform, event.x, event.y)
                queueMoveAbs(transformedPoint[0].toInt(), transformedPoint[1].toInt())
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (scrollY <= -1.0f) {
                    clickButton(Pointer.Button.BUTTON_SCROLL_DOWN)
                } else if (scrollY >= 1.0f) {
                    clickButton(Pointer.Button.BUTTON_SCROLL_UP)
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                when (actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> pressButton(Pointer.Button.BUTTON_LEFT)
                    MotionEvent.BUTTON_SECONDARY -> pressButton(Pointer.Button.BUTTON_RIGHT)
                    MotionEvent.BUTTON_TERTIARY -> pressButton(Pointer.Button.BUTTON_MIDDLE)
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                when (actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> releaseButton(Pointer.Button.BUTTON_LEFT)
                    MotionEvent.BUTTON_SECONDARY -> releaseButton(Pointer.Button.BUTTON_RIGHT)
                    MotionEvent.BUTTON_TERTIARY -> releaseButton(Pointer.Button.BUTTON_MIDDLE)
                }
                return true
            }
        }
        return false
    }

    fun setSimTouchScreen(sim: Boolean) {
        this.simTouchScreen = sim
        xServer.setSimulateTouchScreen(sim)
    }

    fun isSimTouchScreen(): Boolean = simTouchScreen

    fun setScreenTouchMode(mode: Int) {
        setSimTouchScreen(mode == MODE_TOUCHSCREEN)
        if (screenTouchMode == mode) return
        screenTouchMode = mode
        resetInputState()
    }

    fun getScreenTouchMode(): Int = screenTouchMode

    fun setPointerIdsToIgnore(ids: Set<Int>) {
        pointerIdsToIgnore.clear()
        pointerIdsToIgnore.addAll(ids)
    }

    fun setMouseEnabled(enabled: Boolean) {
        this.mouseEnabled = enabled
        if (!enabled) {
            resetInputState()
            xServer.renderer?.setCursorVisible(false)
        } else {
            showCursor()
        }
    }

    fun resetInputState() {
        continueClick = false
        scrolling = false
        scrollAccumY = 0f
        for (i in 0 until 4) {
            fingers[i] = null
        }
        numFingers = 0
        fingerPointerButtonLeft = null
        fingerPointerButtonRight = null
        longPressHandler.removeCallbacks(longPressRunnable)
        longPressActive = false

        runInject {
            if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            }
            if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
            }
            if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_MIDDLE)) {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE)
            }
        }
    }

    fun toggleFullscreen() {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                updateXform(
                    width,
                    height,
                    xServer.screenInfo.width.toInt(),
                    xServer.screenInfo.height.toInt(),
                )
            },
            50L,
        )
    }

    private fun createTransparentBg(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Color.TRANSPARENT.toDrawable())
        addState(intArrayOf(), Color.TRANSPARENT.toDrawable())
    }
}
