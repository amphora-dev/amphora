package com.winlator.cmod.runtime.input.controls;

import java.io.File;

/**
 * Minimal fake-evdev helpers kept for {@code GuestProgramLauncherComponent} env wiring.
 * Amphora routes touch via XServer inject; ring slots stay empty (getRingEnv returns "").
 * Full WinHandler / gamepad writers were removed with the RFC §7 input stub closure.
 */
public final class FakeInputWriter {
  private FakeInputWriter() {}

  public static void prepareRingSlots(File fakeInputDir, int slotCount) {}

  public static String getRingEnv(File fakeInputDir) {
    return "";
  }

  public static void releaseAllRingSlots() {}
}
