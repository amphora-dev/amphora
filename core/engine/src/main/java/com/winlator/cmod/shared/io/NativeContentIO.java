package com.winlator.cmod.shared.io;

import android.content.res.AssetManager;
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

  public static boolean extractAsset(
      int type,
      AssetManager assetManager,
      String assetFile,
      File destination,
      OnExtractFileListener listener) {
    if (assetManager == null || assetFile == null || assetFile.isEmpty() || destination == null) {
      return false;
    }
    return nativeExtractAsset(type, assetManager, assetFile, destination.getAbsolutePath(), listener);
  }

  private static native boolean nativeExtractArchive(
      int type, String sourcePath, String destinationPath, OnExtractFileListener listener);

  private static native boolean nativeExtractAsset(
      int type,
      AssetManager assetManager,
      String assetFile,
      String destinationPath,
      OnExtractFileListener listener);
}
