package com.winlator.cmod.app.config;

import android.content.Context;
import android.os.Environment;
import java.util.List;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class SettingsConfig {

  private SettingsConfig() {}

  public static final String DEFAULT_WINE_DEBUG_CHANNELS = "module,loaddll,seh";
  public static final String DEFAULT_WINE_DEBUG_CLASSES = "err,warn,fixme";
  public static final List<String> WINE_DEBUG_CLASSES = null;
  public static final String DEFAULT_WINLATOR_PATH =
      Environment.getExternalStorageDirectory().getPath() + "/WinNative";
  public static final String DEFAULT_SHORTCUT_EXPORT_PATH = DEFAULT_WINLATOR_PATH + "/Shortcuts";

  public static void resetEmulatorsVersion(Context activity) {}
}
