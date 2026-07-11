package com.winlator.cmod.runtime.input.controls;

import java.nio.ByteBuffer;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class GamepadState {
  public static final int BUTTON_A = 0;
  public static final int BUTTON_B = 1;
  public static final int BUTTON_X = 2;
  public static final int BUTTON_Y = 3;
  public static final int BUTTON_L1 = 4;
  public static final int BUTTON_R1 = 5;
  public static final int BUTTON_SELECT = 6;
  public static final int BUTTON_START = 7;
  public static final int BUTTON_L3 = 8;
  public static final int BUTTON_R3 = 9;
  public static final int BUTTON_L2 = 10;
  public static final int BUTTON_R2 = 11;
  public static final int BUTTON_GUIDE = 12;
  public static final int BUTTON_DPAD_UP = 13;
  public static final int BUTTON_DPAD_DOWN = 14;
  public static final int BUTTON_DPAD_LEFT = 15;
  public static final int BUTTON_DPAD_RIGHT = 16;

  public float thumbLX = 0;
  public float thumbLY = 0;
  public float thumbRX = 0;
  public float thumbRY = 0;
  public float triggerL = 0;
  public float triggerR = 0;
  public final boolean[] dpad = new boolean[4];
  public short buttons = 0;

  public byte getPovHat() {
    return 0;
  }

  public void writeTo(ByteBuffer buffer) {}

  public void setPressed(int buttonIdx, boolean pressed) {}

  public boolean isPressed(int buttonIdx) {
    return false;
  }

  public boolean isButtonPressed(int buttonCode) {
    return false;
  }

  public byte getDPadX() {
    return 0;
  }

  public byte getDPadY() {
    return 0;
  }

  public void copy(GamepadState other) {}
}
