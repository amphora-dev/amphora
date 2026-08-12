package app.amphora.gamesession.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GraphemeCounterTest {
  @Test
  public void countsChineseCharacters() {
    assertEquals(4, GraphemeCounter.count("中文输入"));
  }

  @Test
  public void keepsSurrogateAndCombiningSequencesTogether() {
    assertEquals(1, GraphemeCounter.count("\uD83D\uDE00"));
    assertEquals(1, GraphemeCounter.count("e\u0301"));
  }

  @Test
  public void handlesEmptyInput() {
    assertEquals(0, GraphemeCounter.count(""));
    assertEquals(0, GraphemeCounter.count(null));
  }
}
