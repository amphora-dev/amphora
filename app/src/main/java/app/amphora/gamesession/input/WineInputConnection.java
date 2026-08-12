package app.amphora.gamesession.input;

import android.os.SystemClock;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;

/**
 * Android IME bridge for the Wine display.
 *
 * <p>Composing text remains in this connection's private editor so pinyin and candidate selection
 * never leak into the guest. Only committed text is forwarded to Wine.
 */
public final class WineInputConnection extends BaseInputConnection {
  private static final int MAX_EDITOR_HISTORY = 2048;
  private static final int RETAINED_EDITOR_HISTORY = 1024;
  private static final long FINISH_COMMIT_DEDUP_WINDOW_MS = 250;

  public interface Listener {
    void onCommitText(CharSequence text);

    void onDelete(int beforeLength, int afterLength);

    boolean onSendKeyEvent(KeyEvent event);

    void onEditorAction();

    void onComposingTextChanged(CharSequence text);
  }

  private final Editable editable = new SpannableStringBuilder();
  private final Listener listener;
  private String recentlyFinishedText = "";
  private long recentlyFinishedAtMs;
  private boolean handlingCodePointDeletion;

  public WineInputConnection(View targetView, Listener listener) {
    super(targetView, true);
    this.listener = listener;
    Selection.setSelection(editable, 0);
  }

  @Override
  public Editable getEditable() {
    return editable;
  }

  @Override
  public boolean commitText(CharSequence text, int newCursorPosition) {
    String committed = text != null ? text.toString() : "";
    boolean duplicateFinish =
        !committed.isEmpty()
            && committed.equals(recentlyFinishedText)
            && SystemClock.uptimeMillis() - recentlyFinishedAtMs
                <= FINISH_COMMIT_DEDUP_WINDOW_MS;
    if (duplicateFinish) {
      clearFinishedDedup();
      listener.onComposingTextChanged("");
      trimHistory();
      return true;
    }
    if (!super.commitText(text, newCursorPosition)) return false;
    if (!committed.isEmpty()) listener.onCommitText(committed);
    clearFinishedDedup();
    listener.onComposingTextChanged("");
    trimHistory();
    return true;
  }

  @Override
  public boolean setComposingText(CharSequence text, int newCursorPosition) {
    if (!super.setComposingText(text, newCursorPosition)) return false;
    clearFinishedDedup();
    listener.onComposingTextChanged(text != null ? text.toString() : "");
    return true;
  }

  @Override
  public boolean setComposingRegion(int start, int end) {
    if (!super.setComposingRegion(start, end)) return false;
    clearFinishedDedup();
    int safeStart = Math.max(0, Math.min(start, editable.length()));
    int safeEnd = Math.max(safeStart, Math.min(end, editable.length()));
    listener.onComposingTextChanged(editable.subSequence(safeStart, safeEnd).toString());
    return true;
  }

  @Override
  public boolean finishComposingText() {
    int start = getComposingSpanStart(editable);
    int end = getComposingSpanEnd(editable);
    String committed =
        start >= 0 && end > start ? editable.subSequence(start, end).toString() : "";
    if (!super.finishComposingText()) return false;
    if (!committed.isEmpty()) {
      listener.onCommitText(committed);
      recentlyFinishedText = committed;
      recentlyFinishedAtMs = SystemClock.uptimeMillis();
    } else clearFinishedDedup();
    listener.onComposingTextChanged("");
    trimHistory();
    return true;
  }

  @Override
  public boolean deleteSurroundingText(int beforeLength, int afterLength) {
    if (handlingCodePointDeletion) {
      return super.deleteSurroundingText(beforeLength, afterLength);
    }
    boolean composing = getComposingSpanStart(editable) >= 0;
    int beforeGraphemes = composing ? 0 : countBeforeCursor(beforeLength, false);
    int afterGraphemes = composing ? 0 : countAfterCursor(afterLength, false);
    if (!super.deleteSurroundingText(beforeLength, afterLength)) return false;
    if (!composing && (beforeLength > 0 || afterLength > 0)) {
      clearFinishedDedup();
      listener.onDelete(
          fallbackDeleteCount(beforeGraphemes, beforeLength),
          fallbackDeleteCount(afterGraphemes, afterLength));
    } else if (composing) notifyCurrentComposition();
    return true;
  }

  @Override
  public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
    boolean composing = getComposingSpanStart(editable) >= 0;
    int beforeGraphemes = composing ? 0 : countBeforeCursor(beforeLength, true);
    int afterGraphemes = composing ? 0 : countAfterCursor(afterLength, true);
    boolean deleted;
    handlingCodePointDeletion = true;
    try {
      deleted = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
    } finally {
      handlingCodePointDeletion = false;
    }
    if (!deleted) return false;
    if (!composing && (beforeLength > 0 || afterLength > 0)) {
      clearFinishedDedup();
      listener.onDelete(
          fallbackDeleteCount(beforeGraphemes, beforeLength),
          fallbackDeleteCount(afterGraphemes, afterLength));
    } else if (composing) notifyCurrentComposition();
    return true;
  }

  @Override
  public boolean sendKeyEvent(KeyEvent event) {
    clearFinishedDedup();
    return listener.onSendKeyEvent(event) || super.sendKeyEvent(event);
  }

  @Override
  public boolean performEditorAction(int actionCode) {
    if (actionCode != EditorInfo.IME_ACTION_NONE) {
      clearFinishedDedup();
      listener.onEditorAction();
      return true;
    }
    return super.performEditorAction(actionCode);
  }

  @Override
  public void closeConnection() {
    super.closeConnection();
    reset();
  }

  public void reset() {
    editable.clear();
    Selection.setSelection(editable, 0);
    clearFinishedDedup();
    listener.onComposingTextChanged("");
  }

  private void trimHistory() {
    if (getComposingSpanStart(editable) >= 0 || editable.length() <= MAX_EDITOR_HISTORY) return;
    editable.delete(0, editable.length() - RETAINED_EDITOR_HISTORY);
    Selection.setSelection(editable, editable.length());
  }

  private int countBeforeCursor(int length, boolean codePoints) {
    int cursor = selectionStart();
    if (cursor <= 0 || length <= 0) return 0;
    int start =
        codePoints
            ? editable.toString().offsetByCodePoints(cursor, -Math.min(length, editable.toString().codePointCount(0, cursor)))
            : Math.max(0, cursor - length);
    return GraphemeCounter.count(editable.subSequence(start, cursor));
  }

  private int countAfterCursor(int length, boolean codePoints) {
    int cursor = selectionStart();
    if (cursor >= editable.length() || length <= 0) return 0;
    int end =
        codePoints
            ? editable.toString().offsetByCodePoints(
                cursor,
                Math.min(length, editable.toString().codePointCount(cursor, editable.length())))
            : Math.min(editable.length(), cursor + length);
    return GraphemeCounter.count(editable.subSequence(cursor, end));
  }

  private int selectionStart() {
    int selection = Selection.getSelectionStart(editable);
    return selection >= 0 ? selection : editable.length();
  }

  private static int fallbackDeleteCount(int graphemes, int requested) {
    return graphemes > 0 ? graphemes : Math.max(0, requested);
  }

  private void clearFinishedDedup() {
    recentlyFinishedText = "";
    recentlyFinishedAtMs = 0;
  }

  private void notifyCurrentComposition() {
    int start = getComposingSpanStart(editable);
    int end = getComposingSpanEnd(editable);
    listener.onComposingTextChanged(
        start >= 0 && end > start ? editable.subSequence(start, end).toString() : "");
  }
}
