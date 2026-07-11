package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import android.view.InputDevice;
import java.util.List;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class ControllerManager {

  public static final String PREF_PLAYER_SLOT_PREFIX = "controller_slot_";
  public static final String PREF_ENABLED_SLOTS_PREFIX = "enabled_slot_";
  public static final String PREF_VIBRATE_SLOT_PREFIX = "vibrate_slot_";
  public static final String PREF_VIBRATION_GLOBAL = "vibration_enabled_global";

  private ControllerManager() {}

  public static synchronized ControllerManager getInstance() {
    return null;
  }

  public void init(Context context) {}

  public void scanForDevices() {}

  public void saveAssignments() {}

  public static boolean isGameController(InputDevice d) {
    return false;
  }

  public static String getDeviceIdentifier(InputDevice device) {
    return null;
  }

  public List<InputDevice> getDetectedDevices() {
    return null;
  }

  public int getEnabledPlayerCount() {
    return 0;
  }

  public void assignDeviceToSlot(int slotIndex, InputDevice device) {}

  public void unassignSlot(int slotIndex) {}

  public int getSlotForDevice(int deviceId) {
    return 0;
  }

  public InputDevice getAssignedDeviceForSlot(int slotIndex) {
    return null;
  }

  public void setSlotEnabled(int slotIndex, boolean isEnabled) {}

  public boolean isSlotEnabled(int slotIndex) {
    return false;
  }

  public boolean isVibrationEnabled(int slot) {
    return false;
  }

  public void setVibrationEnabled(int slot, boolean enabled) {}

  public boolean isGlobalVibrationEnabled() {
    return false;
  }

  public void setGlobalVibrationEnabled(boolean enabled) {}
}
