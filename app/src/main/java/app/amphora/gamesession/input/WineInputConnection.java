package app.amphora.gamesession.input;

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

  public interface Listener {
    void onCommitText(CharSequence text);

    void onDelete(int beforeLength, int afterLength);

    boolean onSendKeyEvent(KeyEvent event);

    void onEditorAction();
  }

  private final Editable editable = new SpannableStringBuilder();
  private final Listener listener;

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
    if (!super.commitText(text, newCursorPosition)) return false;
    if (text != null && text.length() > 0) listener.onCommitText(text.toString());
    trimHistory();
    return true;
  }

  @Override
  public boolean setComposingText(CharSequence text, int newCursorPosition) {
    return super.setComposingText(text, newCursorPosition);
  }

  @Override
  public boolean finishComposingText() {
    int start = getComposingSpanStart(editable);
    int end = getComposingSpanEnd(editable);
    String committed =
        start >= 0 && end > start ? editable.subSequence(start, end).toString() : "";
    if (!super.finishComposingText()) return false;
    if (!committed.isEmpty()) listener.onCommitText(committed);
    trimHistory();
    return true;
  }

  @Override
  public boolean deleteSurroundingText(int beforeLength, int afterLength) {
    boolean composing = getComposingSpanStart(editable) >= 0;
    if (!super.deleteSurroundingText(beforeLength, afterLength)) return false;
    if (!composing && (beforeLength > 0 || afterLength > 0)) {
      listener.onDelete(Math.max(0, beforeLength), Math.max(0, afterLength));
    }
    return true;
  }

  @Override
  public boolean sendKeyEvent(KeyEvent event) {
    return listener.onSendKeyEvent(event) || super.sendKeyEvent(event);
  }

  @Override
  public boolean performEditorAction(int actionCode) {
    if (actionCode != EditorInfo.IME_ACTION_NONE) {
      listener.onEditorAction();
      return true;
    }
    return super.performEditorAction(actionCode);
  }

  @Override
  public void closeConnection() {
    super.closeConnection();
    editable.clear();
  }

  private void trimHistory() {
    if (getComposingSpanStart(editable) >= 0 || editable.length() <= MAX_EDITOR_HISTORY) return;
    editable.delete(0, editable.length() - RETAINED_EDITOR_HISTORY);
    Selection.setSelection(editable, editable.length());
  }
}
