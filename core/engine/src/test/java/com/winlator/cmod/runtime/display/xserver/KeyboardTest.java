package com.winlator.cmod.runtime.display.xserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyboardTest {
  @Test
  public void storesMappingsAcrossTheFullX11KeycodeRange() {
    Keyboard keyboard = new Keyboard(null, new XKeycode[0]);

    keyboard.setKeysyms((byte) Keyboard.MIN_KEYCODE, 1, 2);
    keyboard.setKeysyms((byte) Keyboard.MAX_KEYCODE, 3, 4);

    assertEquals(Keyboard.KEYCODE_COUNT * Keyboard.KEYSYMS_PER_KEYCODE, keyboard.keysyms.length);
    assertTrue(keyboard.hasKeysym((byte) Keyboard.MIN_KEYCODE, 2));
    assertTrue(keyboard.hasKeysym((byte) Keyboard.MAX_KEYCODE, 4));
    assertFalse(keyboard.hasKeysym((byte) Keyboard.MAX_KEYCODE, 2));
  }

  @Test
  public void convertsBmpChineseToDirectUnicodeKeysym() {
    assertEquals(0x01004e2d, Keyboard.unicodeCharToKeysym('中'));
    assertEquals(0x01006587, Keyboard.unicodeCharToKeysym('文'));
  }

  @Test
  public void preservesLegacyLatinKeysymsAndFiltersControls() {
    assertEquals('A', Keyboard.unicodeCharToKeysym('A'));
    assertEquals(0x00e9, Keyboard.unicodeCharToKeysym('\u00e9'));
    assertEquals(0, Keyboard.unicodeCharToKeysym('\n'));
  }

  @Test
  public void encodesSupplementaryCharactersAsUtf16SurrogateKeysyms() {
    char[] pair = Character.toChars(0x1f600);

    assertEquals(0x0100d83d, Keyboard.unicodeCharToKeysym(pair[0]));
    assertEquals(0x0100de00, Keyboard.unicodeCharToKeysym(pair[1]));
  }

  @Test
  public void reusesRecentUnicodeKeycodesWithoutRemapping() {
    Keyboard keyboard = new Keyboard(null, new XKeycode[0]);
    XKeycode first = keyboard.selectUnicodeKeycode(0x01004e2d);

    assertSame(first, keyboard.selectUnicodeKeycode(0x01004e2d));
    for (int index = 0; index < 7; index++) {
      keyboard.selectUnicodeKeycode(0x01005000 + index);
    }

    assertSame(first, keyboard.selectUnicodeKeycode(0x01004e2d));
    assertNotEquals(first, keyboard.selectUnicodeKeycode(0x01006000));
  }
}
