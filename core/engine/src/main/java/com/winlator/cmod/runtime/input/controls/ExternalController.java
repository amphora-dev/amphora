package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import org.json.JSONException;
import org.json.JSONObject;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class ExternalController {
  public static final byte IDX_BUTTON_A = 0;
  public static final byte IDX_BUTTON_B = 1;
  public static final byte IDX_BUTTON_L1 = 4;
  public static final byte IDX_BUTTON_L2 = 10;
  public static final byte IDX_BUTTON_L3 = 8;
  public static final byte IDX_BUTTON_R1 = 5;
  public static final byte IDX_BUTTON_R2 = 11;
  public static final byte IDX_BUTTON_R3 = 9;
  public static final byte IDX_BUTTON_SELECT = 6;
  public static final byte IDX_BUTTON_START = 7;
  public static final byte IDX_BUTTON_X = 2;
  public static final byte IDX_BUTTON_Y = 3;
  public static final byte TRIGGER_IS_BUTTON = 0;
  public static final byte TRIGGER_IS_AXIS = 1;
  public static final byte TRIGGER_IS_BOTH = 2;
  public static final HashMap<Byte, Byte> buttonMappings = new HashMap<>();
  public final GamepadState state = new GamepadState();
  public final GamepadState remappedState = new GamepadState();

  public String getName() {
    return null;
  }

  public void setName(String name) {}

  public String getId() {
    return null;
  }

  public void setId(String id) {}

  public byte getTriggerType() {
    return 0;
  }

  public void setTriggerType(byte mode) {}

  public void setContext(Context context) {}

  public void unregisterListener() {}

  public int getDeviceId() {
    return 0;
  }

  public boolean isConnected() {
    return false;
  }

  public void setButtonMapping(byte originalButton, byte mappedButton) {}

  public byte getMappedButton(byte originalButton) {
    return 0;
  }

  public int getControllerBindingCount() {
    return 0;
  }

  public JSONObject toJSONObject() throws JSONException {
    return null;
  }

  @Override
  public boolean equals(Object obj) {
    return false;
  }

  public boolean isXboxController() {
    return false;
  }

  public boolean updateStateFromMotionEvent(MotionEvent event) {
    return false;
  }

  public boolean updateStateFromKeyEvent(KeyEvent event) {
    return false;
  }

  public static ArrayList<ExternalController> getControllers() {
    return null;
  }

  public static ExternalController getController(String id) {
    return null;
  }

  public static ExternalController getController(int deviceId) {
    return null;
  }

  public static boolean isGameController(InputDevice device) {
    return false;
  }

  public static String getPhysicalDeviceIdentifier(InputDevice device) {
    return null;
  }

  public float getCenteredAxis(MotionEvent event, int axis, int historyPos) {
    return 0f;
  }

  public static boolean isJoystickDevice(MotionEvent event) {
    return false;
  }

  public static int getButtonIdxByKeyCode(int keyCode) {
    return 0;
  }

  public static int getButtonIdxByName(String name) {
    return 0;
  }
}
