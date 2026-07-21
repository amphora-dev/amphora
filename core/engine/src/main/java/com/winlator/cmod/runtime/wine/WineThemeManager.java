package com.winlator.cmod.runtime.wine;

/**
 * Desktop theme default string for container JSON.
 * WinNative {@code WineThemeManager.apply} / wallpaper BMP generation was removed
 * for MVP (never wired without {@code xServer.screenInfo}).
 */
public final class WineThemeManager {
  public static final String DEFAULT_DESKTOP_THEME = "LIGHT,IMAGE,#0277bd";

  private WineThemeManager() {}
}
