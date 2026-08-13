package app.amphora.gamesession.input

import com.winlator.cmod.runtime.display.xserver.Pointer
import com.winlator.cmod.runtime.display.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Test

class RtsGestureControllerTest {
    @Test
    fun oneFingerTapClicksLeftAtTouchPosition() {
        val sink = RecordingSink()
        val controller = RtsGestureController(sink)

        controller.onTouch(down(0, contact(0, 30f, 40f)))
        controller.onTouch(up(100, 0, contact(0, 30f, 40f)))

        assertEquals(
            listOf("move:30.0,40.0", "click:BUTTON_LEFT"),
            sink.events,
        )
    }

    @Test
    fun twoFingerTapClicksRight() {
        val sink = RecordingSink()
        val controller = RtsGestureController(sink)

        controller.onTouch(down(0, contact(0, 10f, 20f)))
        controller.onTouch(
            down(
                10,
                contact(0, 10f, 20f),
                contact(1, 30f, 20f),
                changedPointerId = 1,
            ),
        )
        controller.onTouch(
            up(
                80,
                1,
                contact(0, 10f, 20f),
                contact(1, 30f, 20f),
            ),
        )
        controller.onTouch(up(100, 0, contact(0, 10f, 20f)))

        assertEquals(
            listOf("move:10.0,20.0", "click:BUTTON_RIGHT"),
            sink.events,
        )
    }

    @Test
    fun oneFingerDragHoldsLeftButtonUntilRelease() {
        val sink = RecordingSink()
        val controller = RtsGestureController(sink)

        controller.onTouch(down(0, contact(0, 10f, 20f)))
        controller.onTouch(move(50, contact(0, 70f, 80f)))
        controller.onTouch(up(100, 0, contact(0, 70f, 80f)))

        assertEquals(
            listOf(
                "move:10.0,20.0",
                "press:BUTTON_LEFT",
                "move:70.0,80.0",
                "release:BUTTON_LEFT",
            ),
            sink.events,
        )
    }

    @Test
    fun twoFingerPanHoldsDirectionKeyUntilFingerCountChanges() {
        val sink = RecordingSink()
        val controller = RtsGestureController(sink)

        controller.onTouch(down(0, contact(0, 0f, 0f)))
        controller.onTouch(
            down(
                10,
                contact(0, 0f, 0f),
                contact(1, 0f, 100f),
                changedPointerId = 1,
            ),
        )
        controller.onTouch(
            move(
                50,
                contact(0, 100f, 0f),
                contact(1, 100f, 100f),
            ),
        )
        controller.onTouch(
            up(
                80,
                1,
                contact(0, 100f, 0f),
                contact(1, 100f, 100f),
            ),
        )
        controller.onTouch(up(100, 0, contact(0, 100f, 0f)))

        assertEquals(
            listOf("keyDown:KEY_RIGHT", "keyUp:KEY_RIGHT"),
            sink.events,
        )
    }

    @Test
    fun pinchOutProducesScrollUpWithoutRightClick() {
        val sink = RecordingSink()
        val controller = RtsGestureController(sink)

        controller.onTouch(down(0, contact(0, 0f, 0f)))
        controller.onTouch(
            down(
                10,
                contact(0, 0f, 0f),
                contact(1, 100f, 0f),
                changedPointerId = 1,
            ),
        )
        controller.onTouch(
            move(
                50,
                contact(0, -30f, 0f),
                contact(1, 130f, 0f),
            ),
        )
        controller.onTouch(
            up(
                80,
                1,
                contact(0, -30f, 0f),
                contact(1, 130f, 0f),
            ),
        )
        controller.onTouch(up(100, 0, contact(0, -30f, 0f)))

        assertEquals(listOf("click:BUTTON_SCROLL_UP"), sink.events)
    }

    private class RecordingSink : RtsInputSink {
        val events = mutableListOf<String>()

        override fun movePointer(x: Float, y: Float) {
            events += "move:$x,$y"
        }

        override fun pressButton(button: Pointer.Button) {
            events += "press:$button"
        }

        override fun releaseButton(button: Pointer.Button) {
            events += "release:$button"
        }

        override fun clickButton(button: Pointer.Button) {
            events += "click:$button"
        }

        override fun pressKey(key: XKeycode) {
            events += "keyDown:$key"
        }

        override fun releaseKey(key: XKeycode) {
            events += "keyUp:$key"
        }

        override fun openSessionControls() {
            events += "openControls"
        }
    }

    private companion object {
        fun contact(id: Int, x: Float, y: Float) = RtsTouchContact(id, x, y)

        fun down(timeMs: Long, vararg contacts: RtsTouchContact, changedPointerId: Int = contacts.last().id) =
            RtsTouchEvent(
                action = RtsTouchAction.DOWN,
                changedPointerId = changedPointerId,
                contacts = contacts.toList(),
                eventTimeMs = timeMs,
            )

        fun move(timeMs: Long, vararg contacts: RtsTouchContact) = RtsTouchEvent(
            action = RtsTouchAction.MOVE,
            changedPointerId = -1,
            contacts = contacts.toList(),
            eventTimeMs = timeMs,
        )

        fun up(timeMs: Long, changedPointerId: Int, vararg contacts: RtsTouchContact) = RtsTouchEvent(
            action = RtsTouchAction.UP,
            changedPointerId = changedPointerId,
            contacts = contacts.toList(),
            eventTimeMs = timeMs,
        )
    }
}
