package com.winlator.cmod.runtime.input.controls;

import java.io.File;
import java.io.IOException;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class FakeInputWriter {
  public static final short ABS_BRAKE = 10;
  public static final short ABS_GAS = 9;
  public static final short ABS_HAT0X = 16;
  public static final short ABS_HAT0Y = 17;
  public static final short ABS_RX = 3;
  public static final short ABS_RY = 4;
  public static final short ABS_X = 0;
  public static final short ABS_Y = 1;
  public static final short EV_ABS = 3;
  public static final short EV_KEY = 1;
  public static final short EV_MSC = 4;
  public static final short EV_SYN = 0;
  public static final short MSC_SCAN = 4;
  public static final short SYN_REPORT = 0;
  public static final short BTN_A = 304;
  public static final short BTN_B = 305;
  public static final short BTN_X = 307;
  public static final short BTN_Y = 308;
  public static final short BTN_TL = 310;
  public static final short BTN_TR = 311;
  public static final short BTN_SELECT = 314;
  public static final short BTN_START = 315;
  public static final short BTN_THUMBL = 317;
  public static final short BTN_THUMBR = 318;

  public FakeInputWriter(String fakeInputPath, int slot) {}

  public static void prepareRingSlots(File fakeInputDir, int slotCount) {}

  public static String getRingEnv(File fakeInputDir) {
    // Stub (RFC §7): amphora routes input via XServer inject (TouchInputOverlay),
    // not the fakeinput evdev ring. Return "" so the kernel's
    // `if (!getRingEnv(...).isEmpty())` guard skips FAKE_EVDEV_MEMFD_PATHS
    // (returning null would NPE in GuestProgramLauncherComponent.execGuestProgram).
    return "";
  }

  public static void releaseAllRingSlots() {}

  public synchronized boolean open() {
    return false;
  }

  public synchronized void close() {}

  public synchronized void reset() {}

  public synchronized void softRelease() {}

  public synchronized void destroy() {}

  public synchronized void writeGamepadState(GamepadState state) throws IOException {}
}
