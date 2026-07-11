package com.winlator.cmod.runtime.input.ui;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import com.winlator.cmod.runtime.input.controls.Binding;
import com.winlator.cmod.runtime.input.controls.ControlsProfile;
import com.winlator.cmod.runtime.input.controls.ExternalController;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class InputControlsView extends View {

  public InputControlsView(Context context) {
    super(context);
  }

  public InputControlsView(Context context, Handler timeoutHandler, Runnable hideControlsRunnable) {
    super(context);
  }

  public InputControlsView(Context context, boolean focusOnStick) {
    super(context);
  }

  public synchronized ControlsProfile getProfile() {
    return null;
  }

  public synchronized void setProfile(ControlsProfile profile) {}

  public boolean isShowTouchscreenControls() {
    return false;
  }

  public void setShowTouchscreenControls(boolean showTouchscreenControls) {}

  public int getMaxWidth() {
    return 0;
  }

  public int getMaxHeight() {
    return 0;
  }

  public void handleInputEvent(Binding binding, boolean isActionDown) {}

  public void handleInputEvent(
      ExternalController controller, Binding binding, boolean isActionDown) {}

  public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {}

  public void handleInputEvent(
      ExternalController controller, Binding binding, boolean isActionDown, float offset) {}

  public void handleInputEvent(
      ExternalController controller,
      Binding binding,
      boolean isActionDown,
      float offset,
      boolean sendUpdate) {}

  public void handleStickInput(Binding firstBinding, float deltaX, float deltaY) {}

  public void handleStickInput(
      Binding firstBinding, float deltaX, float deltaY, boolean sendUpdate) {}
}
