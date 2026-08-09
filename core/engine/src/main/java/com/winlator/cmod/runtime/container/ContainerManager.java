package com.winlator.cmod.runtime.container;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import app.amphora.core.engine.AppliedMarks;
import com.winlator.cmod.runtime.content.ContentProfile;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.content.SharedDllLinker;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.wine.WineInfo;
import com.winlator.cmod.runtime.wine.WineUtils;
import com.winlator.cmod.shared.io.FileUtils;
import com.winlator.cmod.shared.io.TarCompressorUtils;
import com.winlator.cmod.shared.util.Callback;
import com.winlator.cmod.shared.util.OnExtractFileListener;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

public class ContainerManager {
  private final ArrayList<Container> containers = new ArrayList<>();
  private int maxContainerId = 0;
  private final File homeDir;
  private final Context context;

  private boolean isInitialized = false;

  public ContainerManager(Context context) {
    this.context = context;
    File rootDir = ImageFs.find(context).getRootDir();
    homeDir = new File(rootDir, "home");
    loadContainers();
    isInitialized = true;
  }

  public boolean isInitialized() {
    return isInitialized;
  }

  public ArrayList<Container> getContainers() {
    return containers;
  }

  public void loadContainers() {
    containers.clear();
    maxContainerId = 0;

    File[] files = homeDir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          if (file.getName().startsWith(ImageFs.USER + "-")) {
            try {
              Container container =
                  new Container(
                      Integer.parseInt(file.getName().replace(ImageFs.USER + "-", "")), this);

              container.setRootDir(new File(homeDir, ImageFs.USER + "-" + container.id));
              String configStr = FileUtils.readString(container.getConfigFile());
              if (configStr == null || configStr.trim().isEmpty()) {
                // Empty can happen transiently when another thread is mid-save (fopen("w") truncates first); skip quietly — the next loadContainers picks it up.
                Log.w(
                    "ContainerManager",
                    "Skipping container " + container.id + ": config file empty or unreadable");
                continue;
              }
              JSONObject data = new JSONObject(configStr);
              container.loadData(data);
              containers.add(container);
              maxContainerId = Math.max(maxContainerId, container.id);
            } catch (Exception e) {
              Log.e("ContainerManager", "Error loading container " + file.getName(), e);
            }
          }
        }
      }
    }
  }

  public Context getContext() {
    return context;
  }

  public boolean activateContainer(Container container) {
    Log.d("ContainerManager", "activateContainer: id=" + container.id);
    File containerDir = new File(homeDir, ImageFs.USER + "-" + container.id);
    container.setRootDir(containerDir);
    File file = new File(homeDir, ImageFs.USER);
    String linkTarget = "./" + ImageFs.USER + "-" + container.id;

    // Make C: Drive accessible — 0771 not 0777 to prevent other apps reading file contents
    try {
      Runtime.getRuntime()
          .exec(
              new String[] {
                "chmod", "-R", "0771", new File(containerDir, ".wine/drive_c").getAbsolutePath()
              });
    } catch (Exception e) {
    }

    // Replace the real "xuser" dir with a symlink to the active container. Best-effort
    // migrate of legacy winhandler/wfm if present (Amphora no longer installs them).
    if (file.exists() && !FileUtils.isSymlink(file)) {
      Log.w(
          "ContainerManager",
          "activateContainer: xuser is real dir, migrating essential files to container "
              + container.id);
      migrateEssentialFiles(file, containerDir);
      boolean deleted = FileUtils.delete(file);
      Log.d("ContainerManager", "activateContainer: real xuser dir delete=" + deleted);
      if (!deleted && file.exists()) {
        File backup = new File(homeDir, ImageFs.USER + ".inactive-" + System.currentTimeMillis());
        boolean renamed = file.renameTo(backup);
        Log.w(
            "ContainerManager",
            "activateContainer: real xuser delete failed, rename to "
                + backup.getName()
                + "="
                + renamed);
        if (!renamed && file.exists()) {
          Log.e("ContainerManager", "activateContainer: unable to replace real xuser directory");
          return false;
        }
      }
    } else {
      boolean deleted = file.delete();
      Log.d(
          "ContainerManager",
          "activateContainer: xuser symlink/missing delete="
              + deleted
              + " existed="
              + file.exists());
    }
    FileUtils.symlink(linkTarget, file.getPath());
    boolean symlinkReady = FileUtils.isSymlink(file) && linkTarget.equals(FileUtils.readSymlink(file));
    Log.d(
        "ContainerManager",
        "activateContainer: xuser symlink created, isSymlink="
            + FileUtils.isSymlink(file)
            + " target="
            + FileUtils.readSymlink(file));
    if (!symlinkReady) {
      Log.e(
          "ContainerManager",
          "activateContainer: active xuser does not point to selected container "
              + container.id);
    }
    return symlinkReady;
  }

  private void migrateEssentialFiles(File sourceDir, File destDir) {
    String[] essentialPaths = {
      ".wine/drive_c/windows/winhandler.exe", ".wine/drive_c/windows/wfm.exe"
    };
    for (String path : essentialPaths) {
      File source = new File(sourceDir, path);
      File dest = new File(destDir, path);
      if (source.exists() && !dest.exists()) {
        dest.getParentFile().mkdirs();
        FileUtils.copy(source, dest);
        Log.d("ContainerManager", "Migrated " + path + " to container");
      }
    }
  }

  public void createContainerAsync(
      final JSONObject data, ContentsManager contentsManager, Callback<Container> callback) {
    final Handler handler = new Handler(Looper.getMainLooper());
    Executors.newSingleThreadExecutor()
        .execute(
            () -> {
              final Container container = createContainer(data, contentsManager);
              handler.post(() -> callback.call(container));
            });
  }


  public void removeContainerAsync(Container container, Runnable callback) {
    final Handler handler = new Handler(Looper.getMainLooper());
    Executors.newSingleThreadExecutor()
        .execute(
            () -> {
              removeContainer(container);
              handler.post(callback);
            });
  }

  public Container createContainer(JSONObject data, ContentsManager contentsManager) {
    try {
      int id = maxContainerId + 1;
      File containerDir = new File(homeDir, ImageFs.USER + "-" + id);

      Log.d(
          "ContainerManager",
          "createContainer: homeDir=" + homeDir.getAbsolutePath() + " exists=" + homeDir.exists());

      // If a previous creation crashed, the directory might exist but not be registered.
      while (containerDir.exists()) {
        id++;
        containerDir = new File(homeDir, ImageFs.USER + "-" + id);
      }

      data.put("id", id);
      if (!containerDir.mkdirs()) {
        Log.e(
            "ContainerManager",
            "createContainer: FAILED to create dir: " + containerDir.getAbsolutePath());
        if (!homeDir.exists()) {
          Log.d("ContainerManager", "createContainer: homeDir does not exist, creating...");
          homeDir.mkdirs();
        }
        if (!containerDir.mkdirs() && !containerDir.exists()) {
          Log.e("ContainerManager", "createContainer: STILL failed to create dir after retry");
          return null;
        }
      }
      Log.d(
          "ContainerManager", "createContainer: dir created at " + containerDir.getAbsolutePath());

      Container container = new Container(id, this);
      container.setRootDir(containerDir);
      container.loadData(data);

      String wineVersion = data.getString("wineVersion");
      Log.d("ContainerManager", "createContainer: wineVersion=" + wineVersion);
      container.setWineVersion(wineVersion);

      // Pick emulators by wine arch unless the caller set them: box64 for x86_64, fexcore for arm64ec.
      if (!data.has("emulator") || !data.has("emulator64")) {
          WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
          if (wineInfo.isArm64EC()) {
              if (!data.has("emulator"))   container.setEmulator("fexcore");
              if (!data.has("emulator64")) container.setEmulator64("fexcore");
          } else {
              if (!data.has("emulator"))   container.setEmulator("box64");
              if (!data.has("emulator64")) container.setEmulator64("box64");
          }
          Log.d("ContainerManager", "createContainer: auto-set emulators for arch="
              + wineInfo.getArch() + " emulator=" + container.getEmulator()
              + " emulator64=" + container.getEmulator64());
      }

      // Auto-fill emulator versions when the caller omits them, or DLL extraction is skipped at launch ("No FEXCore version selected") and wineboot crashes.
      if (!data.has("fexcoreVersion") && container.getFEXCoreVersion().isEmpty()) {
          String v = pickNewestInstalledVersion(contentsManager, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE);
          if (!v.isEmpty()) container.setFEXCoreVersion(v);
      }
      if (!data.has("box64Version") && container.getBox64Version().isEmpty()) {
          String emu = container.getEmulator() + "," + container.getEmulator64();
          ContentProfile.ContentType type = emu.contains("wowbox64")
              ? ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
              : ContentProfile.ContentType.CONTENT_TYPE_BOX64;
          String v = pickNewestInstalledVersion(contentsManager, type);
          if (!v.isEmpty()) container.setBox64Version(v);
      }
      Log.d("ContainerManager", "createContainer: versions fexcoreVersion='"
          + container.getFEXCoreVersion() + "' box64Version='" + container.getBox64Version() + "'");

      if (!extractContainerPatternFile(
          container, container.getWineVersion(), contentsManager, containerDir, null, true)) {
        Log.e(
            "ContainerManager",
            "createContainer: extractContainerPatternFile FAILED for wineVersion="
                + container.getWineVersion());
        FileUtils.delete(containerDir);
        return null;
      }
      Log.d("ContainerManager", "createContainer: container pattern extracted successfully");
      WineInfo wineInfoForArch = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
      container.putExtra("wineprefixArch", wineInfoForArch.getArch());
      container.putExtra("wineprefixNeedsUpdate", null);

      // Mark the VC++ 2015-2022 runtime installed once the DLLs are present, or vc_redist runs its installer UI (crashes on ARM64EC theme init, wastes time on x86_64).
      com.winlator.cmod.runtime.wine.WineUtils.seedVcRedistRegistryIfDllsPresent(
          containerDir, wineInfoForArch.isArm64EC());

      container.saveData();
      maxContainerId++;
      containers.add(container);
      return container;
    } catch (Throwable e) {
      Log.e("ContainerManager", "Error creating container", e);
    }
    return null;
  }

  // Newest installed profile's version suffix, or "" if none. The launcher rebuilds names as "<type>-<suffix>", so keep the version code.
  private String pickNewestInstalledVersion(ContentsManager contentsManager, ContentProfile.ContentType type) {
    if (contentsManager == null) return "";
    java.util.List<ContentProfile> profiles = contentsManager.getProfiles(type);
    if (profiles == null || profiles.isEmpty()) return "";
    ContentProfile best = null;
    for (ContentProfile p : profiles) {
      if (!p.isInstalled) continue;
      if (best == null) { best = p; continue; }
      if (p.verCode > best.verCode) { best = p; continue; }
      if (p.verCode == best.verCode
          && p.verName != null
          && best.verName != null
          && p.verName.compareToIgnoreCase(best.verName) > 0) {
        best = p;
      }
    }
    if (best == null) return "";
    String entryName = ContentsManager.getEntryName(best);
    int firstDash = entryName.indexOf('-');
    return firstDash >= 0 ? entryName.substring(firstDash + 1) : entryName;
  }


  private void removeContainer(Container container) {
    // MN-2: deletes the whole container including in-prefix game saves (drive_c/users, Steam userdata); log here so the deletion is visible even on the non-interactive deleteContainer path.
    Log.w(
        "ContainerManager",
        "removeContainer: deleting container " + container.id + " and ALL in-prefix saves at "
            + container.getRootDir());
    if (FileUtils.delete(container.getRootDir())) containers.remove(container);
  }



  public int getNextContainerId() {
    return maxContainerId + 1;
  }

  public Container getContainerById(int id) {
    for (Container container : containers) if (container.id == id) return container;
    return null;
  }

  private boolean extractCommonDlls(
      WineInfo wineInfo,
      String srcName,
      String dstName,
      File containerDir,
      OnExtractFileListener onExtractFileListener,
      boolean linkWineBuiltins)
      throws JSONException {
    File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);
    File contentsRoot = ContentsManager.getContentDir(context);
    boolean allBound = true;

    File[] srcfiles = srcDir.listFiles(file -> file.isFile());

    if (srcfiles != null) {
      for (File file : srcfiles) {
        String dllName = file.getName();
        if (dllName.equals("iexplore.exe")
            && wineInfo.isArm64EC()
            && srcName.equals("aarch64-windows"))
          file = new File(wineInfo.path + "/lib/wine/" + "i386-windows/iexplore.exe");
        if (dllName.equals("tabtip.exe") || dllName.equals("icu.dll")) continue;
        File dstFile = new File(containerDir, ".wine/drive_c/windows/" + dstName + "/" + dllName);
        if (onExtractFileListener != null) {
          dstFile = onExtractFileListener.onExtractFile(dstFile, 0);
          if (dstFile == null) continue;
        }

        if (!linkWineBuiltins) {
          if (!dstFile.exists() && !FileUtils.copy(file, dstFile)) allBound = false;
          continue;
        }

        if (FileUtils.isSymlink(dstFile)) {
          // Keep per-container overrides (DXVK/VKD3D/DirectDraw). Only a link
          // previously owned by Proton may be rebound when the Proton pin moves.
          if (!isWineBuiltinLink(dstFile)) continue;
        } else if (dstFile.exists() && !FileUtils.contentEquals(file, dstFile)) {
          // A native WinComponent or installer-private replacement is intentionally
          // different from Proton and must remain private to this prefix.
          continue;
        }

        if (!SharedDllLinker.link(contentsRoot, file, dstFile)) {
          allBound = false;
          // A missing target is required for a usable prefix; preserve the old copy
          // behavior as a correctness fallback when linking is unavailable.
          if (!dstFile.exists()) FileUtils.copy(file, dstFile);
        }
      }
    }

    if (wineInfo.isArm64EC() && "aarch64-windows".equals(srcName)) {
      File dstDir = new File(containerDir, ".wine/drive_c/windows/" + dstName);
      assertArm64PEMachine(dstDir, "xinput1_4.dll");
      assertArm64PEMachine(dstDir, "dinput8.dll");
    }
    return allBound;
  }

  private boolean isWineBuiltinLink(File target) {
    if (!FileUtils.isSymlink(target)) return false;
    String link = FileUtils.readSymlink(target);
    if (link == null) return false;
    String normalized = link.replace('\\', '/');
    return normalized.contains("/contents/Proton/") || normalized.contains("/contents/Wine/");
  }

  /**
   * Replaces byte-identical Wine builtin copies in a final prefix with links to
   * the immutable installed Proton/Wine component.
   *
   * <p>Call only after the prefix has reached its permanent container path.
   * Relative links created in the repair staging directory would break after
   * that tree is copied into {@code imagefs/home/xuser-*}.
   */
  public boolean linkWineBuiltinFiles(
      Container container, String wineVersion, ContentsManager contentsManager) {
    File containerDir = container != null ? container.getRootDir() : null;
    if (containerDir == null || !containerDir.isDirectory()) return false;
    WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
    if (wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) return false;

    try {
      boolean system32;
      if (wineInfo.isArm64EC()) {
        system32 =
            extractCommonDlls(
                wineInfo, "aarch64-windows", "system32", containerDir, null, true);
      } else {
        system32 =
            extractCommonDlls(
                wineInfo, "x86_64-windows", "system32", containerDir, null, true);
      }
      boolean syswow64 =
          extractCommonDlls(
              wineInfo, "i386-windows", "syswow64", containerDir, null, true);
      return system32 && syswow64;
    } catch (JSONException e) {
      Log.e("ContainerManager", "Cannot bind Wine builtin files", e);
      return false;
    }
  }

  /** Warn loudly if the PE Machine field isn't ARM64 (0xAA64) or ARM64EC (0xA641); missing files are non-fatal. Guards against mis-packaged arm64ec tzsts carrying AMD64 binaries (silent mismatch = joy.cpl fails to load xinput, "Game Controllers" vanishes). */
  private static void assertArm64PEMachine(File dir, String dllName) {
    File f = new File(dir, dllName);
    if (!f.isFile()) return;
    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
      if (raf.length() < 0x40) return;
      raf.seek(0x3c);
      int peOffset = Integer.reverseBytes(raf.readInt());
      if (peOffset < 0 || peOffset + 6 > raf.length()) return;
      raf.seek(peOffset);
      if (raf.readByte() != 'P' || raf.readByte() != 'E'
          || raf.readByte() != 0 || raf.readByte() != 0) return;
      int machine = Short.toUnsignedInt(Short.reverseBytes(raf.readShort()));
      if (machine != 0xAA64 && machine != 0xA641) {
        Log.e(
            "ContainerManager",
            String.format(
                "PE-machine mismatch: %s in %s is 0x%04X (expected 0xAA64 ARM64 or 0xA641 ARM64EC)."
                    + " Controller support (joy.cpl/xinput) will not load. Source tzst is mis-packaged.",
                dllName, dir.getAbsolutePath(), machine));
      }
    } catch (Exception e) {
      Log.w("ContainerManager", "assertArm64PEMachine: " + f.getAbsolutePath() + ": " + e);
    }
  }

  public boolean extractContainerPatternFile(
      Container container,
      String wineVersion,
      ContentsManager contentsManager,
      File containerDir,
      OnExtractFileListener onExtractFileListener) {
    return extractContainerPatternFile(
        container,
        wineVersion,
        contentsManager,
        containerDir,
        onExtractFileListener,
        false);
  }

  private boolean extractContainerPatternFile(
      Container container,
      String wineVersion,
      ContentsManager contentsManager,
      File containerDir,
      OnExtractFileListener onExtractFileListener,
      boolean linkWineBuiltins) {
    Log.d(
        "ContainerManager",
        "extractContainerPatternFile: wineVersion="
            + wineVersion
            + " containerDir="
            + containerDir.getAbsolutePath());
    WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
    Log.d(
        "ContainerManager",
        "extractContainerPatternFile: wineInfo="
            + wineInfo
            + " path="
            + (wineInfo != null ? wineInfo.path : "null"));

    // Step 1: try the versioned container pattern from bundled assets (e.g. "<wineVersion>_container_pattern.tzst").
    String containerPattern = wineVersion + "_container_pattern.tzst";
    boolean result = false;
    try {
      context.getAssets().open(containerPattern).close();
      Log.d("ContainerManager", "extractContainerPatternFile: trying asset: " + containerPattern);
      result =
          TarCompressorUtils.extract(
              TarCompressorUtils.Type.ZSTD,
              context,
              containerPattern,
              containerDir,
              onExtractFileListener);
      Log.d("ContainerManager", "extractContainerPatternFile: asset extraction result=" + result);
    } catch (Exception ignored) {
      Log.d(
          "ContainerManager",
          "extractContainerPatternFile: asset not bundled, trying installed profile prefix pack: "
              + containerPattern);
    }

    // Step 2: If asset extraction failed, look for the prefix pack from the installed custom proton
    if (!result) {
      ContentProfile profile = contentsManager.getProfileByEntryName(wineVersion);
      Log.d(
          "ContainerManager",
          "extractContainerPatternFile: profile lookup for '"
              + wineVersion
              + "' => "
              + (profile != null ? profile.verName : "null"));

      if (profile != null) {
        // Use ContentsManager's install dir — always correct for custom installed protons, unlike wineInfo.path which may fall back to default.
        File profileInstallDir = ContentsManager.getInstallDir(context, profile);
        Log.d(
            "ContainerManager",
            "extractContainerPatternFile: profileInstallDir="
                + profileInstallDir.getAbsolutePath()
                + " exists="
                + profileInstallDir.exists());

        File containerPatternFile;
        if (profile.winePrefixPack != null && !profile.winePrefixPack.isEmpty()) {
          containerPatternFile = new File(profileInstallDir, profile.winePrefixPack);
        } else {
          containerPatternFile = new File(profileInstallDir, "prefixPack.txz");
        }
        Log.d(
            "ContainerManager",
            "extractContainerPatternFile: trying profile prefix pack: "
                + containerPatternFile.getAbsolutePath()
                + " exists="
                + containerPatternFile.exists());

        if (containerPatternFile.exists()) {
          if (containerPatternFile.getName().endsWith(".tzst")) {
            result =
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, containerPatternFile, containerDir);
          } else {
            result =
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.XZ, containerPatternFile, containerDir);
          }
          Log.d(
              "ContainerManager",
              "extractContainerPatternFile: profile prefix pack extraction result=" + result);
        }
      }

      // Also try from wineInfo.path as a secondary fallback (for bundled non-asset protons)
      if (!result && wineInfo != null && wineInfo.path != null && !wineInfo.path.isEmpty()) {
        File wineInfoPrefixPack;
        if (profile != null
            && profile.winePrefixPack != null
            && !profile.winePrefixPack.isEmpty()) {
          wineInfoPrefixPack = new File(wineInfo.path, profile.winePrefixPack);
        } else {
          wineInfoPrefixPack = new File(wineInfo.path, "prefixPack.txz");
        }
        Log.d(
            "ContainerManager",
            "extractContainerPatternFile: trying wineInfo.path fallback: "
                + wineInfoPrefixPack.getAbsolutePath()
                + " exists="
                + wineInfoPrefixPack.exists());
        if (wineInfoPrefixPack.exists()) {
          if (wineInfoPrefixPack.getName().endsWith(".tzst")) {
            result =
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, wineInfoPrefixPack, containerDir);
          } else {
            result =
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.XZ, wineInfoPrefixPack, containerDir);
          }
          Log.d(
              "ContainerManager",
              "extractContainerPatternFile: wineInfo.path fallback extraction result=" + result);
        }
      }
    }

    // Amphora no longer ships container_pattern_common.tzst (Winlator template with
    // fonts / icons / winhandler / embedded ddraw tools). The only supported prefix
    // source is the installed Proton profile's prefixPack (step 2 above).
    if (!result) {
      Log.e(
          "ContainerManager",
          "extractContainerPatternFile: all pattern sources failed for wineVersion="
              + wineVersion
              + " (expected Proton prefixPack.txz under the installed profile)");
    }

    if (result) {
      try {
        if (wineInfo.isArm64EC())
          extractCommonDlls(
              wineInfo,
              "aarch64-windows",
              "system32",
              containerDir,
              onExtractFileListener,
              linkWineBuiltins); // arm64ec only
        else
          extractCommonDlls(
              wineInfo,
              "x86_64-windows",
              "system32",
              containerDir,
              onExtractFileListener,
              linkWineBuiltins);

        extractCommonDlls(
            wineInfo,
            "i386-windows",
            "syswow64",
            containerDir,
            onExtractFileListener,
            linkWineBuiltins);
      } catch (JSONException e) {
        Log.e("ContainerManager", "extractContainerPatternFile: extractCommonDlls failed", e);
        // Don't fail the whole extraction just because of common DLLs — container is still usable
        Log.w(
            "ContainerManager",
            "extractContainerPatternFile: continuing despite extractCommonDlls failure");
      }
      if (!syncWinePrefixUpdateTimestamp(wineInfo, containerDir)) result = false;
    }

    return result;
  }

  /**
   * Wineboot compares this marker with share/wine/wine.inf's mtime. A generic
   * schema PrefixPack cannot carry the right value for every installed Proton
   * package, so bind it when the prefix is materialized instead.
   */
  private boolean syncWinePrefixUpdateTimestamp(WineInfo wineInfo, File containerDir) {
    File wineInf = new File(wineInfo.path, "share/wine/wine.inf");
    File marker = new File(containerDir, ".wine/.update-timestamp");
    if (!wineInf.isFile() || !marker.getParentFile().isDirectory()) {
      Log.e(
          "ContainerManager",
          "Cannot resolve Wine update timestamp source/target: " + wineInf + " -> " + marker);
      return false;
    }
    long timestampSeconds = wineInf.lastModified() / 1000L;
    if (!FileUtils.writeString(marker, timestampSeconds + "\n")) {
      Log.e("ContainerManager", "Cannot write Wine update timestamp: " + marker);
      return false;
    }
    marker.setLastModified(wineInf.lastModified());
    return true;
  }

  public boolean repairContainerWinePrefix(
      Container container,
      String wineVersion,
      ContentsManager contentsManager,
      OnExtractFileListener onExtractFileListener) {
    File containerDir = container.getRootDir();
    if (containerDir == null || !containerDir.isDirectory()) return false;

    File tempDir = FileUtils.createTempFile(context.getCacheDir(), "wineprefix-repair");
    if (!tempDir.mkdirs()) {
      Log.e(
          "ContainerManager",
          "repairContainerWinePrefix: failed to create temp dir " + tempDir.getAbsolutePath());
      return false;
    }

    boolean extracted = false;
    try {
      extracted =
          extractContainerPatternFile(
              container, wineVersion, contentsManager, tempDir, onExtractFileListener);
      if (!extracted) {
        Log.e(
            "ContainerManager",
            "repairContainerWinePrefix: failed to extract repair prefix for " + wineVersion);
        return false;
      }

      File repairedPrefixDir = new File(tempDir, ".wine");
      if (!WineUtils.isPrefixValid(tempDir) || !repairedPrefixDir.isDirectory()) {
        Log.e("ContainerManager", "repairContainerWinePrefix: extracted prefix is still invalid");
        return false;
      }

      // Move the (possibly corrupt) prefix ASIDE, not delete — games store saves inside it and this repair auto-runs at launch, so deleting would destroy saves. Rename old->backup, copy in the repaired prefix, migrate saves across; one backup kept per container as a recovery copy.
      File targetPrefixDir = new File(containerDir, ".wine");
      File backupPrefixDir = new File(containerDir, ".wine.broken-backup");
      boolean movedAside = false;
      if (targetPrefixDir.exists()) {
        if (backupPrefixDir.exists() && !FileUtils.delete(backupPrefixDir)) {
          Log.e(
              "ContainerManager",
              "repairContainerWinePrefix: failed to clear previous prefix backup "
                  + backupPrefixDir.getAbsolutePath());
          return false;
        }
        if (!targetPrefixDir.renameTo(backupPrefixDir)) {
          // Do NOT delete as a fallback — that would destroy in-prefix saves; abort the repair so the container keeps its original prefix (safer failure).
          Log.e(
              "ContainerManager",
              "repairContainerWinePrefix: failed to move existing prefix aside; aborting "
                  + "repair to avoid save-data loss " + targetPrefixDir.getAbsolutePath());
          return false;
        }
        movedAside = true;
      }

      if (!copyWinePrefixTree(repairedPrefixDir, targetPrefixDir)) {
        Log.e("ContainerManager", "repairContainerWinePrefix: failed to copy repaired prefix");
        // Roll back so the container keeps its original prefix rather than a half-written one.
        if (movedAside) {
          FileUtils.delete(targetPrefixDir);
          if (!backupPrefixDir.renameTo(targetPrefixDir)) {
            Log.e(
                "ContainerManager",
                "repairContainerWinePrefix: CRITICAL — failed to restore prefix after copy "
                    + "failure; original prefix preserved at "
                    + backupPrefixDir.getAbsolutePath());
          }
        }
        return false;
      }

      if (!linkWineBuiltinFiles(container, wineVersion, contentsManager)) {
        // Linking is a storage optimization. The repaired regular-file prefix is
        // complete and remains the safe fallback if an individual bind fails.
        Log.w(
            "ContainerManager",
            "repairContainerWinePrefix: Wine builtin linking incomplete; keeping copies");
      }

      // Best-effort: carry in-prefix save data over to the repaired prefix so saves survive without manual recovery.
      if (movedAside) {
        migrateInPrefixSaveData(backupPrefixDir, targetPrefixDir);
      }

      WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
      container.putExtra("wineprefixArch", wineInfo.getArch());
      // 前缀换新：装过标记作废，下次按容器配置重做。
      AppliedMarks.clearOwnedByPrefix(container);
      container.saveData();
      if (movedAside) {
        Log.i(
            "ContainerManager",
            "repairContainerWinePrefix: original prefix preserved for save recovery at "
                + backupPrefixDir.getAbsolutePath());
      }
      return true;
    } finally {
      FileUtils.delete(tempDir);
    }
  }

  /** Copy in-prefix save data from the moved-aside old prefix into the repaired one. Best-effort/non-fatal — the old prefix is kept as backup, so failure means manual recovery, not data loss. */
  private void migrateInPrefixSaveData(File oldPrefixDir, File newPrefixDir) {
    String[] saveSubPaths = {
      "drive_c/users",
      "drive_c/ProgramData",
      "drive_c/Program Files (x86)/Steam/userdata",
    };
    for (String sub : saveSubPaths) {
      File src = new File(oldPrefixDir, sub);
      if (!src.exists()) continue;
      File dst = new File(newPrefixDir, sub);
      try {
        if (!copyWinePrefixTree(src, dst)) {
          Log.w("ContainerManager", "migrateInPrefixSaveData: failed to migrate " + sub);
        }
      } catch (Exception e) {
        Log.w("ContainerManager", "migrateInPrefixSaveData: error migrating " + sub, e);
      }
    }
  }

  private boolean copyWinePrefixTree(File source, File target) {
    if (source == null || target == null || !source.exists()) return false;

    if (FileUtils.isSymlink(source)) {
      File parent = target.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
      FileUtils.symlink(FileUtils.readSymlink(source), target.getAbsolutePath());
      return FileUtils.isSymlink(target);
    }

    if (source.isDirectory()) {
      if (!target.isDirectory() && !target.mkdirs()) return false;
      File[] children = source.listFiles();
      if (children == null) return true;
      for (File child : children) {
        if (!copyWinePrefixTree(child, new File(target, child.getName()))) return false;
      }
      return true;
    }

    return FileUtils.copy(source, target);
  }


  private void runOnUiThread(Runnable action) {
    new Handler(Looper.getMainLooper()).post(action);
  }
}
