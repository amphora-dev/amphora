package app.amphora.gamesession

import app.amphora.core.engine.model.SessionHandle
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PendingSessionActionsTest {
    @Test
    fun attachWithoutPendingRequestReturnsNone() {
        val actions = PendingSessionActions()

        assertEquals(PendingSessionAction.NONE, actions.attach(mockk()))
    }

    @Test
    fun stopRequestedDuringStartingIsDeliveredOnAttach() {
        val actions = PendingSessionActions()
        val handle = mockk<SessionHandle>()

        assertNull(actions.requestStop())

        assertEquals(PendingSessionAction.STOP, actions.attach(handle))
    }

    @Test
    fun pauseRequestedDuringStartingIsDeliveredOnAttach() {
        val actions = PendingSessionActions()
        val handle = mockk<SessionHandle>()

        assertNull(actions.requestPause())

        assertEquals(PendingSessionAction.PAUSE, actions.attach(handle))
    }

    @Test
    fun stopSupersedesPendingPauseAndBlocksLaterResume() {
        val actions = PendingSessionActions()
        val handle = mockk<SessionHandle>()

        actions.requestPause()
        actions.requestStop()

        assertEquals(PendingSessionAction.STOP, actions.attach(handle))
        assertNull(actions.requestResume())
        assertSame(handle, actions.requestStop())
    }

    @Test
    fun resumeBeforeAttachCancelsPendingPause() {
        val actions = PendingSessionActions()

        assertNull(actions.requestPause())
        assertNull(actions.requestResume())

        assertEquals(PendingSessionAction.NONE, actions.attach(mockk()))
    }

    @Test
    fun pendingPauseIsConsumedOnlyOnce() {
        val actions = PendingSessionActions()
        actions.requestPause()

        assertEquals(PendingSessionAction.PAUSE, actions.attach(mockk()))
        assertEquals(PendingSessionAction.NONE, actions.attach(mockk()))
    }

    @Test
    fun attachedHandleReceivesImmediatePauseResumeAndStopRequests() {
        val actions = PendingSessionActions()
        val handle = mockk<SessionHandle>()
        actions.attach(handle)

        assertSame(handle, actions.requestPause())
        assertSame(handle, actions.requestResume())
        assertSame(handle, actions.requestStop())
    }
}
