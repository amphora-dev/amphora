package com.winlator.cmod.app;

import android.app.Activity;
import android.app.Application;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class PluviaApp extends Application {

  public static final Companion Companion = new Companion();

  public static class Companion {
    public PluviaApp getInstance() {
      return null;
    }

    public Activity getCurrentForegroundActivity() {
      return null;
    }

    public boolean isGameSessionActive() {
      return false;
    }
  }
}
