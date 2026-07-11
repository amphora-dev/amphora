package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import com.winlator.cmod.runtime.input.ui.InputControlsView;
import java.io.File;
import java.util.ArrayList;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class ControlsProfile implements Comparable<ControlsProfile> {
  public final int id;

  public ControlsProfile(Context context, int id) {
    this.id = id;
  }

  public String getName() {
    return null;
  }

  public void setName(String name) {}

  public float getCursorSpeed() {
    return 0f;
  }

  public void setCursorSpeed(float cursorSpeed) {}

  public boolean isVirtualGamepad() {
    return false;
  }

  public GamepadState getGamepadState() {
    return null;
  }

  public ExternalController addController(String id) {
    return null;
  }

  public void removeController(ExternalController controller) {}

  public void putController(ExternalController controller) {}

  public ExternalController getController(String id) {
    return null;
  }

  public ExternalController getController(int deviceId) {
    return null;
  }

  @Override
  public String toString() {
    return null;
  }

  @Override
  public int compareTo(ControlsProfile o) {
    return 0;
  }

  public boolean isElementsLoaded() {
    return false;
  }

  public void save() {}

  public static File getProfileFile(Context context, int id) {
    return null;
  }

  public int findColorForBinding(Binding binding) {
    return 0;
  }

  public int getElementCountFromFile() {
    return 0;
  }

  public boolean isTemplate() {
    return false;
  }

  public ArrayList<ExternalController> loadControllers() {
    return null;
  }

  public void loadElements(InputControlsView inputControlsView) {}
}
