package com.winlator.cmod.runtime.input.controls;

import com.winlator.cmod.runtime.display.xserver.Pointer;
import com.winlator.cmod.runtime.display.xserver.XKeycode;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public enum Binding {
  NONE,
  MOUSE_LEFT_BUTTON,
  MOUSE_MIDDLE_BUTTON,
  MOUSE_RIGHT_BUTTON,
  MOUSE_MOVE_LEFT,
  MOUSE_MOVE_RIGHT,
  MOUSE_MOVE_UP,
  MOUSE_MOVE_DOWN,
  MOUSE_SCROLL_UP,
  MOUSE_SCROLL_DOWN,
  KEY_UP,
  KEY_RIGHT,
  KEY_DOWN,
  KEY_LEFT,
  KEY_ENTER,
  KEY_ESC,
  KEY_BKSP,
  KEY_DEL,
  KEY_TAB,
  KEY_SPACE,
  KEY_CTRL_L,
  KEY_CTRL_R,
  KEY_INSERT,
  KEY_SHIFT_L,
  KEY_SHIFT_R,
  KEY_ALT_L,
  KEY_ALT_R,
  KEY_HOME,
  KEY_END,
  KEY_PRTSCN,
  KEY_PG_UP,
  KEY_PG_DOWN,
  KEY_CAPS_LOCK,
  KEY_NUM_LOCK,
  KEY_0,
  KEY_1,
  KEY_2,
  KEY_3,
  KEY_4,
  KEY_5,
  KEY_6,
  KEY_7,
  KEY_8,
  KEY_9,
  KEY_A,
  KEY_B,
  KEY_C,
  KEY_D,
  KEY_E,
  KEY_F,
  KEY_G,
  KEY_H,
  KEY_I,
  KEY_J,
  KEY_K,
  KEY_L,
  KEY_M,
  KEY_N,
  KEY_O,
  KEY_P,
  KEY_Q,
  KEY_R,
  KEY_S,
  KEY_T,
  KEY_U,
  KEY_V,
  KEY_W,
  KEY_X,
  KEY_Y,
  KEY_Z,
  KEY_BRACKET_LEFT,
  KEY_BRACKET_RIGHT,
  KEY_BACKSLASH,
  KEY_SLASH,
  KEY_SEMICOLON,
  KEY_COMMA,
  KEY_PERIOD,
  KEY_APOSTROPHE,
  KEY_KP_ADD,
  KEY_MINUS,
  KEY_GRAVE,
  KEY_F1,
  KEY_F2,
  KEY_F3,
  KEY_F4,
  KEY_F5,
  KEY_F6,
  KEY_F7,
  KEY_F8,
  KEY_F9,
  KEY_F10,
  KEY_F11,
  KEY_F12,
  KEY_KP_0,
  KEY_KP_1,
  KEY_KP_2,
  KEY_KP_3,
  KEY_KP_4,
  KEY_KP_5,
  KEY_KP_6,
  KEY_KP_7,
  KEY_KP_8,
  KEY_KP_9,
  GAMEPAD_BUTTON_A,
  GAMEPAD_BUTTON_B,
  GAMEPAD_BUTTON_X,
  GAMEPAD_BUTTON_Y,
  GAMEPAD_BUTTON_L1,
  GAMEPAD_BUTTON_R1,
  GAMEPAD_BUTTON_SELECT,
  GAMEPAD_BUTTON_START,
  GAMEPAD_BUTTON_L3,
  GAMEPAD_BUTTON_R3,
  GAMEPAD_BUTTON_L2,
  GAMEPAD_BUTTON_R2,
  GAMEPAD_LEFT_THUMB_UP,
  GAMEPAD_LEFT_THUMB_RIGHT,
  GAMEPAD_LEFT_THUMB_DOWN,
  GAMEPAD_LEFT_THUMB_LEFT,
  GAMEPAD_RIGHT_THUMB_UP,
  GAMEPAD_RIGHT_THUMB_RIGHT,
  GAMEPAD_RIGHT_THUMB_DOWN,
  GAMEPAD_RIGHT_THUMB_LEFT,
  GAMEPAD_DPAD_UP,
  GAMEPAD_DPAD_RIGHT,
  GAMEPAD_DPAD_DOWN,
  GAMEPAD_DPAD_LEFT;

  public final XKeycode keycode;

  Binding() {
    this.keycode = XKeycode.KEY_NONE;
  }

  @Override
  public String toString() {
    return null;
  }

  public static Binding fromString(String name) {
    return null;
  }

  public Pointer.Button getPointerButton() {
    return null;
  }

  public boolean isMouse() {
    return false;
  }

  public boolean isKeyboard() {
    return false;
  }

  public boolean isGamepad() {
    return false;
  }

  public boolean isMouseMove() {
    return false;
  }

  public static String[] mouseBindingLabels() {
    return null;
  }

  public static String[] keyboardBindingLabels() {
    return null;
  }

  public static String[] gamepadBindingLabels() {
    return null;
  }

  public static Binding[] mouseBindingValues() {
    return null;
  }

  public static Binding[] keyboardBindingValues() {
    return null;
  }

  public static Binding[] gamepadBindingValues() {
    return null;
  }
}
