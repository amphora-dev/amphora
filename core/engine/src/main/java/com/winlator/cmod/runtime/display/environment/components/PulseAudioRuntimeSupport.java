package com.winlator.cmod.runtime.display.environment.components;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.io.TarCompressorUtils;
import java.io.File;

/** Installs the matched PulseAudio control binary and AAudio modules from the APK asset. */
public final class PulseAudioRuntimeSupport {
  static final String ASSET_PATH = "pulseaudio.tzst";
  static final String ASSET_VERSION =
      "357bb53fbcf91ab2adcd2e6a4b7fc3f2cb95f1555610681b08dbf6d412ac4bd8";

  private static final String MARKER_NAME = ".asset-version";
  private static final String[] REQUIRED_FILES = {
    "pactl",
    "modules/libprotocol-native.so",
    "modules/module-aaudio-sink.so",
    "modules/module-native-protocol-unix.so"
  };
  private static final Object LOCK = new Object();

  private PulseAudioRuntimeSupport() {}

  /**
   * The matched GameNative/WinNative AAudio module is currently linked for 4 KB pages. Keep the
   * ALSA backend on 16 KB devices instead of attempting an ELF load the platform will reject.
   */
  public static boolean isSupportedPlatform() {
    return Os.sysconf(OsConstants._SC_PAGESIZE) <= 4096;
  }

  public static File ensureInstalled(Context context) {
    synchronized (LOCK) {
      File runtimeDir = new File(context.getFilesDir(), "pulseaudio");
      if (isCurrent(runtimeDir)) {
        ensurePactlExecutable(runtimeDir);
        return runtimeDir;
      }

      File staging = new File(context.getFilesDir(), "pulseaudio.installing");
      File backup = new File(context.getFilesDir(), "pulseaudio.previous");
      FileUtils.delete(staging);
      FileUtils.delete(backup);
      if (!staging.mkdirs()) {
        throw new IllegalStateException("Cannot create PulseAudio staging directory: " + staging);
      }

      boolean extracted =
          TarCompressorUtils.extract(
              TarCompressorUtils.Type.ZSTD, context, ASSET_PATH, staging);
      if (!extracted || !hasRequiredFiles(staging)) {
        FileUtils.delete(staging);
        throw new IllegalStateException("PulseAudio runtime asset is missing or incomplete");
      }
      ensurePactlExecutable(staging);
      FileUtils.writeString(new File(staging, MARKER_NAME), ASSET_VERSION);

      boolean oldMoved = !runtimeDir.exists() || runtimeDir.renameTo(backup);
      if (!oldMoved) {
        FileUtils.delete(staging);
        throw new IllegalStateException("Cannot preserve previous PulseAudio runtime");
      }
      if (!staging.renameTo(runtimeDir)) {
        if (backup.exists()) backup.renameTo(runtimeDir);
        FileUtils.delete(staging);
        throw new IllegalStateException("Cannot publish PulseAudio runtime");
      }
      FileUtils.delete(backup);
      return runtimeDir;
    }
  }

  static boolean isCurrent(File runtimeDir) {
    if (!hasRequiredFiles(runtimeDir)) return false;
    File marker = new File(runtimeDir, MARKER_NAME);
    if (!marker.isFile()) return false;
    String version = FileUtils.readString(marker);
    return version != null && ASSET_VERSION.equals(version.trim());
  }

  private static boolean hasRequiredFiles(File runtimeDir) {
    if (!runtimeDir.isDirectory()) return false;
    for (String relativePath : REQUIRED_FILES) {
      if (!new File(runtimeDir, relativePath).isFile()) return false;
    }
    return true;
  }

  private static void ensurePactlExecutable(File runtimeDir) {
    File pactl = new File(runtimeDir, "pactl");
    if (!pactl.canExecute()) FileUtils.chmod(pactl, 0755);
    if (!pactl.canExecute()) {
      throw new IllegalStateException("Cannot make pactl executable: " + pactl);
    }
  }
}
