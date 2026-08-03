package com.winlator.cmod.runtime.display.environment;

/**
 * ImageFS layout version pin (WinNative {@code ImageFsInstaller.LATEST_VERSION}).
 * Amphora extracts imagefs via {@code ImageFsRootfsInstaller}; the Activity / dialog
 * installer UI was removed with the handwritten {@code R} stub cleanup.
 */
public final class ImageFsInstaller {
  public static final byte LATEST_VERSION = 24;

  private ImageFsInstaller() {}
}
