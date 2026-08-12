package app.amphora.gamesession.input;

import java.text.BreakIterator;
import java.util.Locale;

/** Counts user-visible character boundaries for IME deletion forwarding. */
public final class GraphemeCounter {
  private GraphemeCounter() {}

  public static int count(CharSequence text) {
    if (text == null || text.length() == 0) return 0;
    BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
    iterator.setText(text.toString());
    int count = 0;
    for (int boundary = iterator.first();
        boundary != BreakIterator.DONE;
        boundary = iterator.next()) {
      if (boundary > 0) count++;
    }
    return count;
  }
}
