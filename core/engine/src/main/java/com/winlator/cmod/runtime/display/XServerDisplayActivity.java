package com.winlator.cmod.runtime.display;

import android.app.Activity;
import com.winlator.cmod.runtime.display.ui.XServerSurfaceView;
import com.winlator.cmod.runtime.display.xserver.XServer;
import com.winlator.cmod.runtime.input.ui.InputControlsView;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class XServerDisplayActivity extends Activity {

  public XServerDisplayActivity() {}

  public XServer getXServer() {
    return null;
  }

  public XServerSurfaceView getXServerView() {
    return null;
  }

  public InputControlsView getInputControlsView() {
    return null;
  }
}
