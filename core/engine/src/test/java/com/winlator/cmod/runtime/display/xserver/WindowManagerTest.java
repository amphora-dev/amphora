package com.winlator.cmod.runtime.display.xserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.winlator.cmod.runtime.display.xserver.errors.BadValue;
import org.junit.Test;

public class WindowManagerTest {
  @Test
  public void rejectsZeroWidth() {
    BadValue error =
        assertThrows(BadValue.class, () -> WindowManager.validateWindowSize((short) 0, (short) 1));

    assertEquals(0, error.getData());
  }

  @Test
  public void rejectsNegativeHeight() {
    BadValue error =
        assertThrows(BadValue.class, () -> WindowManager.validateWindowSize((short) 1, (short) -1));

    assertEquals(-1, error.getData());
  }

  @Test
  public void acceptsPositiveSize() throws BadValue {
    WindowManager.validateWindowSize((short) 1, Short.MAX_VALUE);
  }
}
