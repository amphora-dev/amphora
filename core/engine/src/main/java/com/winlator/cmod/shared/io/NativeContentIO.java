package com.winlator.cmod.shared.io;

import com.winlator.cmod.shared.util.OnExtractFileListener;
import java.io.File;

public final class NativeContentIO {
  public static final int TYPE_XZ = 0;
  public static final int TYPE_ZSTD = 1;

  static {
    System.loadLibrary("winlator");
  }

  private NativeContentIO() {}

  public static boolean extractArchive(
      int type, File source, File destination, OnExtractFileListener listener) {
    if (source == null || destination == null || !source.isFile()) return false;
    return nativeExtractArchive(
        type, source.getAbsolutePath(), destination.getAbsolutePath(), listener);
  }

  private static native boolean nativeExtractArchive(
      int type, String sourcePath, String destinationPath, OnExtractFileListener listener);
}
