package com.winlator.cmod.runtime.audio;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Keeps Amphora's ALSA-only imagefs layout compatible with Box64 + stock alsa-lib.
 *
 * <p>Box64's native {@code libasound} wrapper opens {@code libasound.so.2}. NDK builds of
 * alsa-lib on Android commonly emit an unversioned {@code libasound.so} SONAME, so the
 * versioned name must exist as a sibling symlink.
 *
 * <p>The android_aserver PCM plugin historically lived at {@code usr/lib/asound_module_*.so}
 * (Winlator naming). Stock alsa-lib loads {@code
 * $ALSA_PLUGIN_DIR/libasound_module_pcm_<name>.so}, and Amphora sets {@code ALSA_PLUGIN_DIR}
 * to {@code usr/lib/alsa-lib}. Bridge both names with relative symlinks.
 */
public final class AlsaRuntimeSupport {
  private static final String TAG = "AlsaRuntimeSupport";

  private AlsaRuntimeSupport() {}

  public static void ensureImageFsLayout(File rootDir) {
    if (rootDir == null) return;
    File libDir = new File(rootDir, "usr/lib");
    if (!libDir.isDirectory()) {
      Log.w(TAG, "Skipping ALSA layout; missing " + libDir);
      return;
    }

    ensureSymlink(
        libDir,
        "libasound.so.2",
        "libasound.so",
        /* requireTarget= */ true);

    File pluginDir = new File(libDir, "alsa-lib");
    if (!pluginDir.isDirectory() && !pluginDir.mkdirs()) {
      Log.e(TAG, "Cannot create ALSA plugin dir: " + pluginDir);
      return;
    }

    File plugin = new File(libDir, "asound_module_pcm_android_aserver.so");
    if (!plugin.isFile()) {
      Log.w(TAG, "android_aserver plugin missing at " + plugin);
      return;
    }

    String relativePlugin = "../asound_module_pcm_android_aserver.so";
    ensureSymlink(
        pluginDir,
        "libasound_module_pcm_android_aserver.so",
        relativePlugin,
        /* requireTarget= */ false);
    ensureSymlink(
        pluginDir,
        "asound_module_pcm_android_aserver.so",
        relativePlugin,
        /* requireTarget= */ false);
  }

  private static void ensureSymlink(
      File parent, String linkName, String target, boolean requireTarget) {
    File link = new File(parent, linkName);
    File targetFile = new File(parent, target);
    if (requireTarget && !targetFile.isFile()) {
      return;
    }
    try {
      if (Files.isSymbolicLink(link.toPath())) {
        String existing = Files.readSymbolicLink(link.toPath()).toString();
        if (target.equals(existing) && Files.exists(link.toPath())) {
          return;
        }
      } else if (link.isFile()) {
        return;
      }
      Files.deleteIfExists(link.toPath());
      Os.symlink(target, link.getAbsolutePath());
      Log.i(TAG, "Linked " + link.getName() + " -> " + target);
    } catch (ErrnoException | IOException | RuntimeException e) {
      Log.e(TAG, "Failed linking " + link + " -> " + target, e);
      try {
        Files.deleteIfExists(link.toPath());
      } catch (IOException ignored) {
      }
    }
  }
}
