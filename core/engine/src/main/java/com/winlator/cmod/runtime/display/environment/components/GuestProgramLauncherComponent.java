package com.winlator.cmod.runtime.display.environment.components;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Process;
import android.util.Log;
import androidx.preference.PreferenceManager;
import app.amphora.core.engine.Box64Runtime;
import app.amphora.core.engine.EmulatorRuntime;
import com.winlator.cmod.runtime.compat.box64.Box64Preset;
import com.winlator.cmod.runtime.compat.box64.Box64PresetManager;
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePreset;
import com.winlator.cmod.runtime.compat.fexcore.FEXCorePresetManager;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.content.ContentProfile;
import com.winlator.cmod.runtime.content.ContentsManager;
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig;
import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.runtime.system.GPUInformation;
import com.winlator.cmod.runtime.system.ProcessHelper;
import com.winlator.cmod.runtime.wine.EnvVars;
import com.winlator.cmod.runtime.wine.WineInfo;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.net.InetAddress;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
  private static final String TAG = "GuestProgramLauncherComponent";
  private static final Object lock = new Object();
  private String guestExecutable;
  private int pid = -1;
  private int launchGeneration = 0;
  private EnvVars envVars;
  private WineInfo wineInfo;
  private String box64Preset = Box64Preset.PERFORMANCE;
  private final ContentsManager contentsManager;
  private final ContentProfile wineProfile;
  private Container container;
  private File workingDir;

  public void setWorkingDir(File workingDir) {
    this.workingDir = workingDir;
  }

  public void setWineInfo(WineInfo wineInfo) {
    this.wineInfo = wineInfo;
  }

  public void setContainer(Container container) {
    this.container = container;
  }

  private void extractBox64Files() {
    ImageFs imageFs = environment.getImageFs();
    Log.i(
        "GuestProgramLauncherComponent",
        "Launch runtime selected: Box64 version=" + container.getBox64Version());
    if (Box64Runtime.ensureApplied(container, imageFs, contentsManager)) {
      container.saveData();
    }
  }

  private void extractEmulatorsDlls() {
    EmulatorRuntime.Result result =
        EmulatorRuntime.ensureApplied(container, environment.getImageFs(), contentsManager);
    if (result.getChanged()) {
      container.saveData();
    }
  }

  public GuestProgramLauncherComponent(
      ContentsManager contentsManager, ContentProfile wineProfile) {
    this.contentsManager = contentsManager;
    this.wineProfile = wineProfile;
  }


  @Override
  public void start() {
    synchronized (lock) {
      long startTime = System.currentTimeMillis();
      if (wineInfo.isArm64EC()) {
        extractEmulatorsDlls();
      } else extractBox64Files();
      copyDefaultBox64RCFile();

      launchGeneration++;
      pid = execGuestProgram();
      if (pid == -1) {
        // ProcessHelper.exec swallows pb.start() failures and returns -1. Surface it so
        // WineEngineImpl.launch -> markFailed fires and awaitReady is honest instead of
        // silently marking the session RUNNING with no guest process.
        throw new IllegalStateException(
            "Guest process failed to start (exec returned -1; see logcat for "
                + "linker64 errors / 'Permission denied' / missing binary)");
      }
      Log.d(
          TAG,
          "Guest process started with pid="
              + pid
              + " after launch prep "
              + (System.currentTimeMillis() - startTime)
              + "ms");
    }
  }

  private void copyDefaultBox64RCFile() {
    Context context = environment.getContext();
    ImageFs imageFs = ImageFs.find(context);
    File rootDir = imageFs.getRootDir();
    String assetPath = "box86_64/default.box64rc";
    FileUtils.copy(context, assetPath, new File(rootDir, "/etc/config.box64rc"));
  }

  @Override
  public void stop() {
    synchronized (lock) {
      if (pid != -1) {
        Log.d("GuestProgramLauncherComponent", "Stopping guest process pid=" + pid);
        Process.killProcess(pid);
        pid = -1;
      } else {
        Log.d("GuestProgramLauncherComponent", "Stop requested with no tracked guest process");
      }
    }
  }

  public void setGuestExecutable(String guestExecutable) {
    this.guestExecutable = guestExecutable;
  }

  public void setEnvVars(EnvVars envVars) {
    this.envVars = envVars;
  }

  public void setBox64Preset(String box64Preset) {
    this.box64Preset = box64Preset;
  }

  private static String mergePreloadValue(String baseValue, String overrideValue) {
    if (overrideValue == null || overrideValue.isEmpty()) {
      return baseValue == null ? "" : baseValue;
    }
    if (baseValue == null || baseValue.isEmpty()) {
      return overrideValue;
    }
    if (overrideValue.equals(baseValue)) {
      return baseValue;
    }
    return baseValue + ":" + overrideValue;
  }

  private static String appendFirstExistingPreload(String ldPreload, File[] candidates) {
    for (File candidate : candidates) {
      if (candidate.exists()) {
        return mergePreloadValue(ldPreload, candidate.getAbsolutePath());
      }
    }
    return ldPreload;
  }

  private void mergeExternalEnvVars(EnvVars envVars, String protectedLdPreload) {
    if (this.envVars == null) {
      return;
    }

    if (this.envVars.has("MANGOHUD")) {
      this.envVars.remove("MANGOHUD");
    }

    if (this.envVars.has("MANGOHUD_CONFIG")) {
      this.envVars.remove("MANGOHUD_CONFIG");
    }

    String overrideLdPreload = this.envVars.get("LD_PRELOAD");
    envVars.putAll(this.envVars);

    if (protectedLdPreload != null && !protectedLdPreload.isEmpty()) {
      envVars.put("LD_PRELOAD", mergePreloadValue(protectedLdPreload, overrideLdPreload));
    }
  }


  private int execGuestProgram() {
    final int gen = launchGeneration;
    Context context = environment.getContext();
    ImageFs imageFs = environment.getImageFs();
    File rootDir = imageFs.getRootDir();

    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
    boolean emulatorLogs = preferences.getBoolean("enable_emulator_logs", false);
    boolean fexLogActive = wineInfo != null && wineInfo.isArm64EC()
        && (container == null || !"wowbox64".equalsIgnoreCase(container.getEmulator()));
    boolean enableBox64Logs = emulatorLogs && !fexLogActive;
    boolean enableFexcoreLogs = emulatorLogs && fexLogActive;

    EnvVars envVars = new EnvVars();

    addBox64EnvVars(envVars, enableBox64Logs);
    if (wineInfo != null && wineInfo.isArm64EC()) {
      envVars.putAll(FEXCorePresetManager.getEnvVars(context, FEXCorePreset.PERFORMANCE));

      if (enableFexcoreLogs) {
        // FEXCore is silent by default. Enable logging to stderr so its output is
        // captured by the in-game logs pane and the per-session fexcore_*.txt log.
        envVars.put("FEX_SILENTLOG", "0");
        envVars.put("FEX_OUTPUTLOG", "stderr");
      }
    }

    String renderer = GPUInformation.getRenderer(null, null);

    if (renderer.contains("Mali")) envVars.put("BOX64_MMAP32", "0");

    if (envVars.get("BOX64_MMAP32").equals("1") && !wineInfo.isArm64EC()) {
      Log.d("GuestProgramLauncherComponent", "Disabling map memory placed");
      envVars.put("WRAPPER_DISABLE_PLACED", "1");
    }

    // Setting up essential environment variables for Wine
    envVars.put("HOME", imageFs.home_path);
    envVars.put("USER", ImageFs.USER);
    envVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
    envVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
    StringBuilder libraryPath = new StringBuilder(rootDir.getPath()).append("/usr/lib");
    if (envVars.has("PULSE_SERVER") || (this.envVars != null && this.envVars.has("PULSE_SERVER"))) {
      // Box64 wraps libpulse and resolves the matching AArch64 client from the APK.
      // Caller env (PULSE_SERVER) is merged later, so check it here too.
      libraryPath.append(':').append(context.getApplicationInfo().nativeLibraryDir);
    }
    libraryPath.append(":/system/lib64");
    envVars.put("LD_LIBRARY_PATH", libraryPath.toString());
    envVars.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
    envVars.put("GST_PLUGIN_PATH", rootDir.getPath() + "/usr/lib/gstreamer-1.0");
    envVars.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");
    applyVulkanRuntimePaths(envVars, rootDir.getPath());
    envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
    envVars.put("PREFIX", rootDir.getPath() + "/usr");
    // See earlier DISPLAY note — unix: absolute path required for non-chroot imagefs.
    envVars.put("DISPLAY", "unix:" + rootDir.getPath() + UnixSocketConfig.XSERVER_PATH);
    envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
    envVars.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
    envVars.put(
        "ALSA_CONFIG_PATH",
        rootDir.getPath()
            + "/usr/share/alsa/alsa.conf"
            + ":"
            + rootDir.getPath()
            + "/usr/etc/alsa/conf.d/android_aserver.conf");
    envVars.put("ALSA_PLUGIN_DIR", rootDir.getPath() + "/usr/lib/alsa-lib");
    envVars.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
    envVars.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
    envVars.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
    // No WINE_X11FORCEGLX or WINE_USE_EGL: OpenGL goes through the Mesa WGL
    // opengl32.dll that XServerWineSessionPreparer links into the prefix
    // (opengl32=n,b), not through Wine's GLX/EGL backends.
    envVars.put("WINE_GST_NO_GL", "1");
    envVars.put("SteamGameId", "0");
    envVars.put("PROTON_AUDIO_CONVERT", "0");
    envVars.put("PROTON_VIDEO_CONVERT", "0");
    envVars.put("PROTON_DEMUX", "0");

    String winePath = imageFs.getWinePath() + "/bin";

    Log.d("GuestProgramLauncherComponent", "WinePath is " + winePath);

    // WinNative Proton packages use small shell launchers in bin/ which call
    // readlink/dirname before exec'ing the real x86_64 loader. The full
    // WinNative imagefs carries those coreutils; Amphora's minimal imagefs does
    // not. Bionic exposes both through Android toybox under /system/bin.
    envVars.put("PATH", winePath + ":" + rootDir.getPath() + "/usr/bin:/system/bin");

    envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);

    String primaryDNS = "8.8.4.4";
    java.util.List<String> orderedDns = new java.util.ArrayList<>();
    try {
      ConnectivityManager connectivityManager =
          (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
      android.net.Network activeNetwork =
          connectivityManager != null ? connectivityManager.getActiveNetwork() : null;
      android.net.LinkProperties linkProps =
          activeNetwork != null ? connectivityManager.getLinkProperties(activeNetwork) : null;
      java.util.List<InetAddress> dnsServers =
          linkProps != null ? linkProps.getDnsServers() : null;
      if (dnsServers != null && !dnsServers.isEmpty()) {
        for (InetAddress dns : dnsServers) {
          if (dns instanceof java.net.Inet4Address) {
            String a = dns.getHostAddress();
            if (a != null && !a.isEmpty()) orderedDns.add(a);
          }
        }
        for (InetAddress dns : dnsServers) {
          if (!(dns instanceof java.net.Inet4Address)) {
            String a = dns.getHostAddress();
            if (a != null && !a.isEmpty()) {
              int pct = a.indexOf('%');
              if (pct >= 0) a = a.substring(0, pct);
              orderedDns.add(a);
            }
          }
        }
        if (!orderedDns.isEmpty()) primaryDNS = orderedDns.get(0);
      }
    } catch (Exception e) {
      Log.w("GuestLauncher", "DNS capture failed, using fallback " + primaryDNS, e);
    }
    if (orderedDns.isEmpty()) {
      orderedDns.add("8.8.4.4");
      orderedDns.add("1.1.1.1");
    }
    envVars.put("ANDROID_RESOLV_DNS", primaryDNS);
    envVars.put("WINE_NEW_NDIS", "1");

    // Refresh /usr/etc/resolv.conf and /usr/etc/hosts on every launch.
    // The shipped imagefs ships stale DNS and a broken localhost mapping; rewrite
    // them here so Linux-side tools inside the prefix (curl, openssl, busybox) and
    // anything else that consults hosts/resolv.conf see the correct values.
    try {
      File etcDir = imageFs.getEtcDir();
      if (etcDir.isDirectory() || etcDir.mkdirs()) {
        StringBuilder resolv = new StringBuilder();
        resolv.append("# Generated at launch by WinNative from Android DNS.\n");
        for (String dns : orderedDns) {
          resolv.append("nameserver ").append(dns).append('\n');
        }
        resolv.append("options edns0 timeout:2 attempts:2\n");
        FileUtils.writeString(new File(etcDir, "resolv.conf"), resolv.toString());

        FileUtils.writeString(
            new File(etcDir, "hosts"),
            "127.0.0.1\tlocalhost\n"
                + "::1\t\tlocalhost ip6-localhost ip6-loopback\n"
                + "fe00::0\t\tip6-localnet\n"
                + "ff00::0\t\tip6-mcastprefix\n"
                + "ff02::1\t\tip6-allnodes\n"
                + "ff02::2\t\tip6-allrouters\n");
      }
    } catch (Exception e) {
      Log.w("GuestLauncher", "Failed to refresh resolv.conf/hosts in imagefs", e);
    }

    String ld_preload;
    if (!new File(imageFs.getLibDir(), "libandroid-sysvshm.so").exists()) {
      ld_preload = "";
    } else {
      ld_preload = imageFs.getLibDir() + "/libandroid-sysvshm.so";
    }
    // Some Proton builds dlopen "libSDL2-2.0.so.0" (standard SONAME on desktop Linux).
    // The imagefs ships "libSDL2-2.0.so" without the .0 suffix.  Create a symlink so
    // any winebus.so build can find SDL regardless of the exact name it tries.
    File sdlSo = new File(imageFs.getLibDir(), "libSDL2-2.0.so");
    File sdlSoLink = new File(imageFs.getLibDir(), "libSDL2-2.0.so.0");
    if (sdlSo.exists() && !sdlSoLink.exists()) {
      FileUtils.symlink(sdlSo.getName(), sdlSoLink.getPath());
      Log.d("GuestLauncher", "Created SDL symlink: " + sdlSoLink.getPath());
    }

    // Preload OEM Vulkan ICD deps that otherwise fail lazy symbol resolution.
    // Keep paths within default linker namespace permitted_paths.
    File[] jpegCandidates = new File[] {
        new File("/system/lib64/libjpeg.so"),
        new File("/system_ext/lib64/libjpeg.so"),
    };
    ld_preload = appendFirstExistingPreload(ld_preload, jpegCandidates);

    File[] cryptoCandidates = new File[] {
        new File("/system/lib64/libcrypto.so"),
        new File("/system_ext/lib64/libcrypto.so"),
        new File(imageFs.getLibDir(), "libcrypto.so.3"),
    };
    ld_preload = appendFirstExistingPreload(ld_preload, cryptoCandidates);


    Log.d("GuestLauncher", "Final LD_PRELOAD: " + ld_preload);
    envVars.put("LD_PRELOAD", ld_preload);

    // Preserve launcher-owned LD_PRELOAD while merging upstream env (driver/DXVK/wrapper).
    mergeExternalEnvVars(envVars, envVars.get("LD_PRELOAD"));
    // Amphora wineandroid host: do not point Wine at the Java fake X server.
    // Caller sets AMPHORA_WINEANDROID=1 (host binds abstract \0\Device\WineAndroid; no host.sock).
    if ("1".equals(envVars.get("AMPHORA_WINEANDROID"))) {
      envVars.remove("DISPLAY");
      envVars.remove("ANDROID_SYSVSHM_SERVER");
      envVars.remove("GST_PLUGIN_FEATURE_RANK");
      // The preparer sets VK_ICD_FILENAMES for every driver but System. A guest ICD
      // (wrapper / Turnip / Leegao) runs under the imagefs Khronos loader, which has
      // no Android WSI: VK_LAYER_AMPHORA_wsi adds VK_KHR_android_surface. System keeps
      // the platform loader, which has it.
      boolean guestIcd = envVars.has("VK_ICD_FILENAMES");
      Log.i(
          TAG,
          "AMPHORA_WINEANDROID=1: dropped DISPLAY / ANDROID_SYSVSHM_SERVER / "
              + "GST_PLUGIN_FEATURE_RANK (no Java XServerComponent); Vulkan "
              + (guestIcd
                  ? "imagefs loader + " + envVars.get("VK_ICD_FILENAMES") + " + VK_LAYER_AMPHORA_wsi"
                  : "platform loader /system/lib64/libvulkan.so")
              + "; LD_PRELOAD libamphora_wsi.so");
      applyWineAndroidVulkanEnv(context, envVars);
    }
    normalizeSyncEnvVars(envVars);
    if (wineInfo == null || !wineInfo.isArm64EC()) {
      configureBox64RcEnv(envVars, rootDir);
    }
    if (wineInfo != null && wineInfo.isArm64EC()) {
      FEXCorePresetManager.normalizeSmcChecksEnvVars(envVars, this.envVars);
    }

    String emulator = container.getEmulator();
    String emulator64 = container.getEmulator64();

    if (wineInfo.isArm64EC()) {
      emulator64 = container.getEmulator64();
    }

    repairRuntimeExecutablePermissions(context, imageFs);

    String command = "";
    String overriddenCommand = envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
    if (overriddenCommand.isEmpty()) {
      if (wineInfo.isArm64EC()) {
        command = winePath + "/" + guestExecutable;
        // Normalize defensively in case a stale/legacy emulator value slipped past
        // Container.normalizeEmulatorFieldsForArch (e.g. external write to
        // .container JSON). Treat anything that isn't literally "wowbox64" — case
        // and whitespace insensitive — as fexcore.
        String emu32 = (emulator == null) ? "" : emulator.trim().toLowerCase(java.util.Locale.ROOT);
        if ("wowbox64".equals(emu32)) {
          envVars.put("HODLL", "wowbox64.dll");
        } else {
          if (!"fexcore".equals(emu32)) {
            Log.w("GuestProgramLauncherComponent",
                    "Unrecognized arm64ec 32-bit emulator='" + emulator
                            + "', defaulting HODLL=libwow64fex.dll");
          }
          envVars.put("HODLL", "libwow64fex.dll");
        }
      } else {
        command = imageFs.getBinDir() + "/box64 " + guestExecutable;
      }
    } else {
      String[] parts = overriddenCommand.split(";");
      for (String part : parts) {
        command = command + part + " ";
      }
      command = command.trim();
    }

    File box64File = new File(rootDir, "/usr/bin/box64");
    if (box64File.exists()) {
      FileUtils.chmod(box64File, 0755);
    }

    // wineandroid: write a keeper script (no sh -c quoting). The keeper is the
    // session leader (setsid -w) and waits for the wine launch command, so the
    // tracked guest pid ends exactly when the program does; the rest of the
    // Wine tree (explorer, wineserver) follows within about a second and the
    // termination callback's reap window collects it. Wine's server directory
    // cannot be used as a liveness signal: its lock and socket files stay
    // behind after wineserver exits.
    if ("1".equals(envVars.get("AMPHORA_WINEANDROID")) && !command.isEmpty()) {
      File keeper = new File(imageFs.getRootDir(), "tmp/wineandroid-keep.sh");
      File keeperDir = keeper.getParentFile();
      if (keeperDir != null) keeperDir.mkdirs();
      String script = "#!/system/bin/sh\n" + command + " &\n" + "wait $!\n";
      if (!FileUtils.writeString(keeper, script)) {
        Log.e(TAG, "failed to write wineandroid keeper " + keeper);
      } else {
        FileUtils.chmod(keeper, 0755);
        command = "/system/bin/setsid -w /system/bin/sh " + keeper.getAbsolutePath();
        Log.i(TAG, "AMPHORA_WINEANDROID=1: setsid keeper " + keeper.getAbsolutePath());
      }
    }

    Log.d(
        "GuestProgramLauncherComponent",
        "Launch env excerpt: "
            + "ADRENOTOOLS_DRIVER_PATH="
            + envVars.get("ADRENOTOOLS_DRIVER_PATH")
            + " "
            + "ADRENOTOOLS_DRIVER_NAME="
            + envVars.get("ADRENOTOOLS_DRIVER_NAME")
            + " "
            + "VK_ICD_FILENAMES="
            + envVars.get("VK_ICD_FILENAMES")
            + " "
            + "WRAPPER_VK_VERSION="
            + envVars.get("WRAPPER_VK_VERSION")
            + " "
            + "WINEDLLOVERRIDES="
            + envVars.get("WINEDLLOVERRIDES")
            + " "
            + "MESA_VK_WSI_DEBUG="
            + envVars.get("MESA_VK_WSI_DEBUG"));

    return ProcessHelper.exec(
        command,
        envVars.toStringArray(),
        workingDir != null ? workingDir : rootDir,
        (status) -> {
          synchronized (lock) {
            if (gen == launchGeneration) pid = -1;
          }

          ProcessHelper.drainDeadChildren("guest process termination callback");
          ProcessHelper.scheduleDeadChildReapSweep("guest process termination callback", 3000, 200);
        });
  }

  private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
    envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
    envVars.put("BOX64_DYNAREC", "1");

    if (enableLogs) {
      envVars.put("BOX64_LOG", "1");
      envVars.put("BOX64_DYNAREC_MISSING", "1");
    }

    envVars.putAll(Box64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
    // BOX64_X11GLX=1 makes box64 answer XQueryExtension("GLX") with "present" no
    // matter what the server says. Amphora's X server has no GLX, so that is a lie
    // that only moves the failure later: a caller probes, believes it, and dies on
    // its first real GLX request. It existed for the old xlib-GLX libGL; OpenGL now
    // goes through EGL, which never asks about GLX.
    envVars.put("BOX64_X11GLX", "0");
    configureBox64RcEnv(envVars, ImageFs.find(environment.getContext()).getRootDir());

    if (container != null) {
      String cpuList = container.getCPUList(true);
      if (cpuList != null && !cpuList.isEmpty()) {
        envVars.put("BOX64_CPULIST", cpuList);
        envVars.put("BOX86_CPULIST", cpuList);
      }
    }
  }


  /**
   * System driver: put {@code wineandroid/vkloader} (libvulkan.so / .so.1 symlinks to
   * the platform loader) in front of {@code LD_LIBRARY_PATH}, so box64/win32u and the
   * WSI helper load {@code /system/lib64/libvulkan.so}.
   */
  static void applyWineAndroidSystemVulkanEnv(EnvVars envVars, String vkLoaderDir) {
    String ld = envVars.get("LD_LIBRARY_PATH");
    envVars.put("LD_LIBRARY_PATH", vkLoaderDir + (ld.isEmpty() ? "" : ":" + ld));
  }

  private static void applyWineAndroidSystemVulkan(Context context, EnvVars envVars) {
    File systemVulkan = new File("/system/lib64/libvulkan.so");
    if (!systemVulkan.isFile()) {
      Log.w(TAG, "AMPHORA_WINEANDROID: missing " + systemVulkan.getAbsolutePath());
      return;
    }
    File vkDir = new File(context.getFilesDir(), "wineandroid/vkloader");
    if (!vkDir.isDirectory() && !vkDir.mkdirs()) {
      Log.w(TAG, "AMPHORA_WINEANDROID: cannot create " + vkDir.getAbsolutePath());
      return;
    }
    File link = new File(vkDir, "libvulkan.so");
    FileUtils.symlink(systemVulkan.getAbsolutePath(), link.getAbsolutePath());
    /* box64 (for win32u) and the WSI helper load SONAME libvulkan.so.1. */
    FileUtils.symlink(
        systemVulkan.getAbsolutePath(), new File(vkDir, "libvulkan.so.1").getAbsolutePath());
    applyWineAndroidSystemVulkanEnv(envVars, vkDir.getAbsolutePath());
    Log.i(
        TAG,
        "AMPHORA_WINEANDROID: LD_LIBRARY_PATH prefix "
            + vkDir.getAbsolutePath()
            + " -> "
            + systemVulkan.getAbsolutePath());
  }

  static final String WSI_LAYER_MANIFEST = "amphora_wsi.json";

  /**
   * Implicit-layer manifest for {@code VK_LAYER_AMPHORA_wsi} in {@code libamphora_wsi.so}
   * ({@code amphora_vklayer.inc}). {@code api_version} is the imagefs loader's, so the
   * loader never treats the layer as older than the app.
   */
  static String wineAndroidWsiLayerManifest(String libraryPath) {
    String path = libraryPath.replace("\\", "\\\\").replace("\"", "\\\"");
    return "{\n"
        + "  \"file_format_version\": \"1.2.0\",\n"
        + "  \"layer\": {\n"
        + "    \"name\": \"VK_LAYER_AMPHORA_wsi\",\n"
        + "    \"type\": \"GLOBAL\",\n"
        + "    \"library_path\": \"" + path + "\",\n"
        + "    \"api_version\": \"1.4.313\",\n"
        + "    \"implementation_version\": \"1\",\n"
        + "    \"description\": \"VK_KHR_android_surface on Amphora wineandroid windows\",\n"
        + "    \"instance_extensions\": [\n"
        + "      { \"name\": \"VK_KHR_android_surface\", \"spec_version\": \"6\" }\n"
        + "    ],\n"
        + "    \"disable_environment\": { \"AMPHORA_WSI_LAYER_DISABLE\": \"1\" }\n"
        + "  }\n"
        + "}\n";
  }

  /**
   * The imagefs loader is a Linux build: its list of known instance extensions has no
   * VK_KHR_android_surface, and vkCreateInstance rejects unknown names even when an
   * enabled layer provides them unless the filter is off.
   */
  static void applyWineAndroidWsiLayerEnv(EnvVars envVars, String layerDir) {
    envVars.put("VK_ADD_IMPLICIT_LAYER_PATH", layerDir);
    envVars.put("VK_LOADER_DISABLE_INST_EXT_FILTER", "1");
  }

  /** Guest ICD: write the layer manifest for this install's nativeLibraryDir. */
  private static void applyWineAndroidWsiLayer(Context context, EnvVars envVars) {
    File helper = new File(context.getApplicationInfo().nativeLibraryDir, "libamphora_wsi.so");
    File layerDir = new File(context.getFilesDir(), "wineandroid/vklayer");
    if (!layerDir.isDirectory() && !layerDir.mkdirs()) {
      Log.e(TAG, "AMPHORA_WINEANDROID: cannot create " + layerDir.getAbsolutePath());
      return;
    }
    File manifest = new File(layerDir, WSI_LAYER_MANIFEST);
    if (!FileUtils.writeString(manifest, wineAndroidWsiLayerManifest(helper.getAbsolutePath()))) {
      Log.e(TAG, "AMPHORA_WINEANDROID: cannot write " + manifest.getAbsolutePath());
      return;
    }
    applyWineAndroidWsiLayerEnv(envVars, layerDir.getAbsolutePath());
    Log.i(TAG, "AMPHORA_WINEANDROID: VK_ADD_IMPLICIT_LAYER_PATH " + layerDir.getAbsolutePath());
  }

  /**
   * PE Win32 WSI helper is aarch64-native; Box64 guests cannot dlopen it. Host-side
   * {@code LD_PRELOAD} of {@code nativeLibraryDir/libamphora_wsi.so} (APK-packaged)
   * runs its ctor in the box64 process (unix socket present bridge). Prefer this
   * over replacing imagefs {@code libandroid-sysvshm.so} with a ctor shim.
   */
  static void applyWineAndroidWsiHelperPreloadEnv(EnvVars envVars, String helperPath) {
    if (helperPath == null || helperPath.isEmpty()) {
      return;
    }
    envVars.put("LD_PRELOAD", mergePreloadValue(helperPath, envVars.get("LD_PRELOAD")));
  }

  private static void applyWineAndroidWsiHelperPreload(Context context, EnvVars envVars) {
    File helper = new File(context.getApplicationInfo().nativeLibraryDir, "libamphora_wsi.so");
    if (!helper.isFile()) {
      Log.w(TAG, "AMPHORA_WINEANDROID: missing WSI helper " + helper.getAbsolutePath());
      return;
    }
    applyWineAndroidWsiHelperPreloadEnv(envVars, helper.getAbsolutePath());
    Log.i(TAG, "AMPHORA_WINEANDROID: LD_PRELOAD " + helper.getAbsolutePath());
  }

  /**
   * The whole guest-Vulkan slice of a wineandroid launch: guest ICDs get the
   * WSI implicit layer (manifest + VK_ADD_IMPLICIT_LAYER_PATH + the inst-ext
   * filter off), System gets the platform-loader LD_LIBRARY_PATH prefix, and
   * every guest gets the WSI helper LD_PRELOAD. Shared with the headless
   * {@code GuestVulkanProbe} so the probe sees exactly what a session sees.
   */
  public static void applyWineAndroidVulkanEnv(Context context, EnvVars envVars) {
    boolean guestIcd = envVars.has("VK_ICD_FILENAMES");
    if (guestIcd) {
      applyWineAndroidWsiLayer(context, envVars);
    } else {
      applyWineAndroidSystemVulkan(context, envVars);
    }
    applyWineAndroidWsiHelperPreload(context, envVars);
  }

  /**
   * Vulkan runtime paths every wineandroid process needs: the imagefs Khronos
   * loader layer dirs and the wrapper ICD's runtime dirs. Shared with
   * {@code GuestVulkanProbe}.
   */
  public static void applyVulkanRuntimePaths(EnvVars envVars, String rootDir) {
    envVars.put(
        "VK_LAYER_PATH",
        rootDir
            + "/usr/share/vulkan/implicit_layer.d"
            + ":"
            + rootDir
            + "/usr/share/vulkan/explicit_layer.d");
    envVars.put("WRAPPER_LAYER_PATH", rootDir + "/usr/lib");
    envVars.put("WRAPPER_CACHE_PATH", rootDir + "/usr/var/cache");
  }


  /**
   * Valve Proton sync defaults (mirrors WinNative {@code normalizeSyncEnvVars}):
   * strip {@code WINEFSYNC}, force {@code PROTON_NO_FSYNC=1}, default {@code WINEESYNC=1}.
   * Explicit {@code WINEESYNC=0}/{@code false} disables esync. Drop dead
   * {@code WINEESYNC_WINLATOR} (Winlator-only; ignored by Valve Proton).
   */
  public static void normalizeSyncEnvVars(EnvVars envVars) {
    envVars.remove("WINEFSYNC");
    envVars.put("PROTON_NO_FSYNC", "1");
    envVars.remove("WINEESYNC_WINLATOR");

    String esyncVal = envVars.get("WINEESYNC");
    boolean esyncOff = "0".equals(esyncVal) || "false".equalsIgnoreCase(esyncVal);
    boolean ntSync =
        "1".equals(envVars.get("WINENTSYNC")) || "1".equals(envVars.get("PROTON_USE_NTSYNC"));

    if (ntSync) {
      envVars.put("WINENTSYNC", "1");
      envVars.put("PROTON_USE_NTSYNC", "1");
    } else {
      envVars.remove("WINENTSYNC");
      envVars.remove("PROTON_USE_NTSYNC");
    }

    if (esyncOff) {
      envVars.remove("WINEESYNC");
      envVars.put("PROTON_NO_ESYNC", "1");
    } else {
      envVars.put("WINEESYNC", "1");
      envVars.remove("PROTON_NO_ESYNC");
    }
  }

  static void configureBox64RcEnv(EnvVars envVars, File rootDir) {
    // BOX64_NORCFILES disables every rc file, including an explicit BOX64_RCFILE.
    // Keep the launcher-owned rc path authoritative after external env merging.
    envVars.remove("BOX64_NORCFILES");
    envVars.put("BOX64_RCFILE", new File(rootDir, "etc/config.box64rc").getPath());
  }

  private void repairRuntimeExecutablePermissions(Context context, ImageFs imageFs) {
    try {
      if (wineProfile != null) {
        ContentsManager.repairInstalledContentPermissions(context, wineProfile);
      } else {
        File wineBinDir = new File(imageFs.getWinePath(), "bin");
        if (wineBinDir.isDirectory()) {
          File[] binaries = wineBinDir.listFiles();
          if (binaries != null) {
            for (File file : binaries) {
              if (file.isFile()) FileUtils.chmod(file, 0755);
            }
          }
        }
      }
    } catch (Exception e) {
      Log.w("GuestProgramLauncherComponent", "Failed to repair runtime executable permissions", e);
    }
  }

  public int getPid() {
    synchronized (lock) {
      return pid;
    }
  }
}
