package app.amphora.gamesession.input

import com.winlator.cmod.runtime.display.xserver.Pointer
import com.winlator.cmod.runtime.display.xserver.XKeycode
import kotlin.math.abs
import kotlin.math.hypot

internal enum class RtsTouchAction {
    DOWN,
    MOVE,
    UP,
    CANCEL,
}

internal data class RtsTouchContact(
    val id: Int,
    val x: Float,
    val y: Float,
)

internal data class RtsTouchEvent(
    val action: RtsTouchAction,
    val changedPointerId: Int,
    val contacts: List<RtsTouchContact>,
    val eventTimeMs: Long,
)

internal interface RtsInputSink {
    fun movePointer(x: Float, y: Float)

    fun pressButton(button: Pointer.Button)

    fun releaseButton(button: Pointer.Button)

    fun clickButton(button: Pointer.Button)

    fun pressKey(key: XKeycode)

    fun releaseKey(key: XKeycode)

    fun openSessionControls()
}

/**
 * Strategy-game touch gestures using only X11 pointer and keyboard injection.
 *
 * One finger selects and box-drags, two fingers pan with arrow keys or pinch to zoom, three
 * fingers middle-click, and four fingers open the session controls.
 */
internal class RtsGestureController(
    private val sink: RtsInputSink,
) {
    private data class Point(val x: Float, val y: Float)

    private val contacts = linkedMapOf<Int, Point>()
    private var sessionStartMs = 0L
    private var maxFingerCount = 0
    private var gestureStart = Point(0f, 0f)
    private var lastCentroid = Point(0f, 0f)
    private var lastDistance = 0f
    private var zoomAccumulator = 0f
    private var dragActive = false
    private var gestureConsumed = false
    private var movedBeyondTap = false
    private var pressedPanKey: XKeycode? = null

    fun onTouch(event: RtsTouchEvent): Boolean {
        when (event.action) {
            RtsTouchAction.DOWN -> onDown(event)
            RtsTouchAction.MOVE -> onMove(event)
            RtsTouchAction.UP -> onUp(event)
            RtsTouchAction.CANCEL -> cancel()
        }
        return true
    }

    fun cancel() {
        releaseContinuousInputs()
        resetSession()
    }

    private fun onDown(event: RtsTouchEvent) {
        if (contacts.isEmpty()) {
            resetSession()
            sessionStartMs = event.eventTimeMs
        } else {
            releaseContinuousInputs()
        }
        updateContacts(event.contacts)
        if (contacts.isEmpty()) return
        maxFingerCount = maxOf(maxFingerCount, contacts.size)
        rebaseGesture()
    }

    private fun onMove(event: RtsTouchEvent) {
        updateContacts(event.contacts)
        if (contacts.isEmpty()) return

        val centroid = centroid()
        val travel = distance(gestureStart, centroid)
        if (travel > TAP_TRAVEL_MAX) movedBeyondTap = true
        when (contacts.size) {
            1 -> handleOneFingerMove(centroid, travel)
            2 -> handleTwoFingerMove(centroid, travel)
        }
        lastCentroid = centroid
    }

    private fun onUp(event: RtsTouchEvent) {
        updateContacts(event.contacts)
        if (contacts.isNotEmpty()) lastCentroid = centroid()
        contacts.remove(event.changedPointerId)
        if (contacts.isEmpty()) {
            finishSession(event.eventTimeMs)
        } else {
            releaseContinuousInputs()
            rebaseGesture()
        }
    }

    private fun handleOneFingerMove(centroid: Point, travel: Float) {
        if (!dragActive && travel >= DRAG_THRESHOLD) {
            dragActive = true
            gestureConsumed = true
            sink.movePointer(gestureStart.x, gestureStart.y)
            sink.pressButton(Pointer.Button.BUTTON_LEFT)
        }
        if (dragActive) sink.movePointer(centroid.x, centroid.y)
    }

    private fun handleTwoFingerMove(centroid: Point, travel: Float) {
        val distance = contactDistance()
        zoomAccumulator += distance - lastDistance
        while (abs(zoomAccumulator) >= ZOOM_STEP) {
            val zoomIn = zoomAccumulator > 0
            sink.clickButton(
                if (zoomIn) {
                    Pointer.Button.BUTTON_SCROLL_UP
                } else {
                    Pointer.Button.BUTTON_SCROLL_DOWN
                },
            )
            zoomAccumulator += if (zoomIn) -ZOOM_STEP else ZOOM_STEP
            gestureConsumed = true
        }
        lastDistance = distance

        if (travel >= PAN_THRESHOLD) {
            gestureConsumed = true
            updatePanKey(centroid.x - gestureStart.x, centroid.y - gestureStart.y)
        } else {
            updatePanKey(0f, 0f)
        }
    }

    private fun updatePanKey(dx: Float, dy: Float) {
        val desired =
            when {
                abs(dx) < PAN_THRESHOLD && abs(dy) < PAN_THRESHOLD -> null
                abs(dx) > abs(dy) && dx > 0 -> XKeycode.KEY_RIGHT
                abs(dx) > abs(dy) -> XKeycode.KEY_LEFT
                dy > 0 -> XKeycode.KEY_DOWN
                else -> XKeycode.KEY_UP
            }
        if (desired == pressedPanKey) return
        pressedPanKey?.let(sink::releaseKey)
        pressedPanKey = desired
        desired?.let(sink::pressKey)
    }

    private fun finishSession(eventTimeMs: Long) {
        val wasContinuous = gestureConsumed || dragActive || pressedPanKey != null
        releaseContinuousInputs()
        val tapDuration = eventTimeMs - sessionStartMs
        if (!wasContinuous && !movedBeyondTap && tapDuration <= TAP_DURATION_MAX_MS) {
            when (maxFingerCount) {
                1 -> {
                    sink.movePointer(lastCentroid.x, lastCentroid.y)
                    sink.clickButton(Pointer.Button.BUTTON_LEFT)
                }
                2 -> {
                    sink.movePointer(lastCentroid.x, lastCentroid.y)
                    sink.clickButton(Pointer.Button.BUTTON_RIGHT)
                }
                3 -> {
                    sink.movePointer(lastCentroid.x, lastCentroid.y)
                    sink.clickButton(Pointer.Button.BUTTON_MIDDLE)
                }
                4 -> sink.openSessionControls()
            }
        }
        resetSession()
    }

    private fun releaseContinuousInputs() {
        if (dragActive) sink.releaseButton(Pointer.Button.BUTTON_LEFT)
        dragActive = false
        pressedPanKey?.let(sink::releaseKey)
        pressedPanKey = null
    }

    private fun updateContacts(updated: List<RtsTouchContact>) {
        val updatedIds = updated.mapTo(mutableSetOf()) { it.id }
        contacts.keys.retainAll(updatedIds)
        updated.forEach { contacts[it.id] = Point(it.x, it.y) }
    }

    private fun rebaseGesture() {
        val centroid = centroid()
        gestureStart = centroid
        lastCentroid = centroid
        lastDistance = contactDistance()
        zoomAccumulator = 0f
    }

    private fun centroid(): Point {
        if (contacts.isEmpty()) return lastCentroid
        var x = 0f
        var y = 0f
        contacts.values.forEach {
            x += it.x
            y += it.y
        }
        return Point(x / contacts.size, y / contacts.size)
    }

    private fun contactDistance(): Float {
        if (contacts.size < 2) return 0f
        val iterator = contacts.values.iterator()
        return distance(iterator.next(), iterator.next())
    }

    private fun resetSession() {
        contacts.clear()
        sessionStartMs = 0L
        maxFingerCount = 0
        gestureStart = Point(0f, 0f)
        lastCentroid = Point(0f, 0f)
        lastDistance = 0f
        zoomAccumulator = 0f
        dragActive = false
        gestureConsumed = false
        movedBeyondTap = false
        pressedPanKey = null
    }

    private fun distance(first: Point, second: Point): Float =
        hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()).toFloat()

    private companion object {
        const val TAP_DURATION_MAX_MS = 300L
        const val TAP_TRAVEL_MAX = 30f
        const val DRAG_THRESHOLD = 40f
        const val PAN_THRESHOLD = 40f
        const val ZOOM_STEP = 40f
    }
}
