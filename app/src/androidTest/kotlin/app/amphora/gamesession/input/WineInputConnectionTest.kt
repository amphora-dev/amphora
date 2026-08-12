package app.amphora.gamesession.input

import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WineInputConnectionTest {
    @Test
    fun commitsOnlyFinalCandidateAndPublishesComposition() {
        val listener = RecordingListener()
        val connection = connection(listener)

        connection.setComposingText("zhong", 1)
        connection.commitText("中", 1)

        assertEquals(listOf("zhong", ""), listener.compositions)
        assertEquals(listOf("中"), listener.commits)
    }

    @Test
    fun suppressesImmediateCommitAfterFinishComposing() {
        val listener = RecordingListener()
        val connection = connection(listener)

        connection.setComposingText("中文", 1)
        connection.finishComposingText()
        connection.commitText("中文", 1)

        assertEquals(listOf("中文"), listener.commits)
        assertEquals("中文", connection.editable.toString())
    }

    @Test
    fun deletesSurrogatePairAsOneVisibleCharacter() {
        val listener = RecordingListener()
        val connection = connection(listener)

        connection.commitText("\uD83D\uDE00", 1)
        listener.deletes.clear()
        connection.deleteSurroundingText(2, 0)

        assertEquals(listOf(1 to 0), listener.deletes)
    }

    private fun connection(listener: RecordingListener): WineInputConnection = WineInputConnection(
        View(ApplicationProvider.getApplicationContext()),
        listener,
    )

    private class RecordingListener : WineInputConnection.Listener {
        val commits = mutableListOf<String>()
        val compositions = mutableListOf<String>()
        val deletes = mutableListOf<Pair<Int, Int>>()

        override fun onCommitText(text: CharSequence) {
            commits += text.toString()
        }

        override fun onDelete(beforeLength: Int, afterLength: Int) {
            deletes += beforeLength to afterLength
        }

        override fun onSendKeyEvent(event: KeyEvent): Boolean = true

        override fun onEditorAction() = Unit

        override fun onComposingTextChanged(text: CharSequence) {
            compositions += text.toString()
        }
    }
}
