package app.amphora.gamesession.input

import android.os.SystemClock
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo

/**
 * Android IME bridge for the Wine display.
 *
 * Composing text remains in this connection's private editor so pinyin and candidate selection
 * never leak into the guest. Only committed text is forwarded to Wine.
 */
class WineInputConnection(targetView: View, private val listener: Listener) : BaseInputConnection(targetView, true) {
    interface Listener {
        fun onCommitText(text: CharSequence)

        fun onDelete(beforeLength: Int, afterLength: Int)

        fun onSendKeyEvent(event: KeyEvent): Boolean

        fun onEditorAction()

        fun onComposingTextChanged(text: CharSequence)
    }

    private val editor: Editable = SpannableStringBuilder()
    private var recentlyFinishedText = ""
    private var recentlyFinishedAtMs = 0L
    private var handlingCodePointDeletion = false

    init {
        Selection.setSelection(editor, 0)
    }

    override fun getEditable(): Editable = editor

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val committed = text?.toString().orEmpty()
        val duplicateFinish =
            committed.isNotEmpty() &&
                committed == recentlyFinishedText &&
                SystemClock.uptimeMillis() - recentlyFinishedAtMs <= FINISH_COMMIT_DEDUP_WINDOW_MS
        if (duplicateFinish) {
            clearFinishedDedup()
            listener.onComposingTextChanged("")
            trimHistory()
            return true
        }
        if (!super.commitText(text, newCursorPosition)) return false
        if (committed.isNotEmpty()) listener.onCommitText(committed)
        clearFinishedDedup()
        listener.onComposingTextChanged("")
        trimHistory()
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!super.setComposingText(text, newCursorPosition)) return false
        clearFinishedDedup()
        listener.onComposingTextChanged(text?.toString().orEmpty())
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (!super.setComposingRegion(start, end)) return false
        clearFinishedDedup()
        val safeStart = start.coerceIn(0, editor.length)
        val safeEnd = end.coerceIn(safeStart, editor.length)
        listener.onComposingTextChanged(editor.subSequence(safeStart, safeEnd).toString())
        return true
    }

    override fun finishComposingText(): Boolean {
        val start = getComposingSpanStart(editor)
        val end = getComposingSpanEnd(editor)
        val committed =
            if (start >= 0 && end > start) {
                editor.subSequence(start, end).toString()
            } else {
                ""
            }
        if (!super.finishComposingText()) return false
        if (committed.isNotEmpty()) {
            listener.onCommitText(committed)
            recentlyFinishedText = committed
            recentlyFinishedAtMs = SystemClock.uptimeMillis()
        } else {
            clearFinishedDedup()
        }
        listener.onComposingTextChanged("")
        trimHistory()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (handlingCodePointDeletion) {
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        val composing = getComposingSpanStart(editor) >= 0
        val beforeGraphemes = if (composing) 0 else countBeforeCursor(beforeLength, false)
        val afterGraphemes = if (composing) 0 else countAfterCursor(afterLength, false)
        if (!super.deleteSurroundingText(beforeLength, afterLength)) return false
        if (!composing && (beforeLength > 0 || afterLength > 0)) {
            clearFinishedDedup()
            listener.onDelete(
                fallbackDeleteCount(beforeGraphemes, beforeLength),
                fallbackDeleteCount(afterGraphemes, afterLength),
            )
        } else if (composing) {
            notifyCurrentComposition()
        }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val composing = getComposingSpanStart(editor) >= 0
        val beforeGraphemes = if (composing) 0 else countBeforeCursor(beforeLength, true)
        val afterGraphemes = if (composing) 0 else countAfterCursor(afterLength, true)
        val deleted: Boolean
        handlingCodePointDeletion = true
        try {
            deleted = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        } finally {
            handlingCodePointDeletion = false
        }
        if (!deleted) return false
        if (!composing && (beforeLength > 0 || afterLength > 0)) {
            clearFinishedDedup()
            listener.onDelete(
                fallbackDeleteCount(beforeGraphemes, beforeLength),
                fallbackDeleteCount(afterGraphemes, afterLength),
            )
        } else if (composing) {
            notifyCurrentComposition()
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        clearFinishedDedup()
        return listener.onSendKeyEvent(event) || super.sendKeyEvent(event)
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        if (actionCode != EditorInfo.IME_ACTION_NONE) {
            clearFinishedDedup()
            listener.onEditorAction()
            return true
        }
        return super.performEditorAction(actionCode)
    }

    override fun closeConnection() {
        super.closeConnection()
        reset()
    }

    fun reset() {
        editor.clear()
        Selection.setSelection(editor, 0)
        clearFinishedDedup()
        listener.onComposingTextChanged("")
    }

    private fun trimHistory() {
        if (getComposingSpanStart(editor) >= 0 || editor.length <= MAX_EDITOR_HISTORY) return
        editor.delete(0, editor.length - RETAINED_EDITOR_HISTORY)
        Selection.setSelection(editor, editor.length)
    }

    private fun countBeforeCursor(length: Int, codePoints: Boolean): Int {
        val cursor = selectionStart()
        if (cursor <= 0 || length <= 0) return 0
        val start =
            if (codePoints) {
                val text = editor.toString()
                val codePointLength = length.coerceAtMost(text.codePointCount(0, cursor))
                text.offsetByCodePoints(cursor, -codePointLength)
            } else {
                (cursor - length).coerceAtLeast(0)
            }
        return GraphemeCounter.count(editor.subSequence(start, cursor))
    }

    private fun countAfterCursor(length: Int, codePoints: Boolean): Int {
        val cursor = selectionStart()
        if (cursor >= editor.length || length <= 0) return 0
        val end =
            if (codePoints) {
                val text = editor.toString()
                val codePointLength =
                    length.coerceAtMost(text.codePointCount(cursor, editor.length))
                text.offsetByCodePoints(cursor, codePointLength)
            } else {
                (cursor + length).coerceAtMost(editor.length)
            }
        return GraphemeCounter.count(editor.subSequence(cursor, end))
    }

    private fun selectionStart(): Int {
        val selection = Selection.getSelectionStart(editor)
        return if (selection >= 0) selection else editor.length
    }

    private fun clearFinishedDedup() {
        recentlyFinishedText = ""
        recentlyFinishedAtMs = 0L
    }

    private fun notifyCurrentComposition() {
        val start = getComposingSpanStart(editor)
        val end = getComposingSpanEnd(editor)
        listener.onComposingTextChanged(
            if (start >= 0 && end > start) editor.subSequence(start, end).toString() else "",
        )
    }

    private companion object {
        const val MAX_EDITOR_HISTORY = 2048
        const val RETAINED_EDITOR_HISTORY = 1024
        const val FINISH_COMMIT_DEDUP_WINDOW_MS = 250L

        fun fallbackDeleteCount(graphemes: Int, requested: Int): Int =
            if (graphemes > 0) graphemes else requested.coerceAtLeast(0)
    }
}
