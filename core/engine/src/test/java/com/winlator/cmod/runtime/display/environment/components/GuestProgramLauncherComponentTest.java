package com.winlator.cmod.runtime.display.environment.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.winlator.cmod.runtime.wine.EnvVars;
import java.io.File;
import org.junit.Test;

public class GuestProgramLauncherComponentTest {
  @Test
  public void explicitBox64RcIsNotDisabledByNoRcFiles() {
    EnvVars envVars = new EnvVars();
    envVars.put("BOX64_NORCFILES", "1");
    envVars.put("BOX64_RCFILE", "/caller/ignored.box64rc");

    GuestProgramLauncherComponent.configureBox64RcEnv(envVars, new File("/imagefs"));

    assertFalse(envVars.has("BOX64_NORCFILES"));
    assertEquals("/imagefs/etc/config.box64rc", envVars.get("BOX64_RCFILE"));
  }

  @Test
  public void wineandroidSystemVulkanEnvPrefixesPlatformLoaderDir() {
    EnvVars envVars = new EnvVars();
    envVars.put("LD_LIBRARY_PATH", "/imagefs/usr/lib:/system/lib64");

    GuestProgramLauncherComponent.applyWineAndroidSystemVulkanEnv(envVars, "/files/wineandroid/vkloader");

    assertEquals(
        "/files/wineandroid/vkloader:/imagefs/usr/lib:/system/lib64", envVars.get("LD_LIBRARY_PATH"));
  }

  @Test
  public void wineandroidWsiLayerEnvAddsImplicitLayerPath() {
    EnvVars envVars = new EnvVars();
    envVars.put("VK_ICD_FILENAMES", "/imagefs/usr/share/vulkan/icd.d/wrapper_icd.aarch64.json");
    envVars.put("ADRENOTOOLS_DRIVER_NAME", "libvulkan_freedreno.so");

    GuestProgramLauncherComponent.applyWineAndroidWsiLayerEnv(envVars, "/files/wineandroid/vklayer");

    assertEquals("/files/wineandroid/vklayer", envVars.get("VK_ADD_IMPLICIT_LAYER_PATH"));
    assertEquals("1", envVars.get("VK_LOADER_DISABLE_INST_EXT_FILTER"));
    assertEquals(
        "/imagefs/usr/share/vulkan/icd.d/wrapper_icd.aarch64.json", envVars.get("VK_ICD_FILENAMES"));
    assertEquals("libvulkan_freedreno.so", envVars.get("ADRENOTOOLS_DRIVER_NAME"));
  }

  @Test
  public void wineandroidWsiLayerManifestDeclaresAndroidSurface() {
    String manifest =
        GuestProgramLauncherComponent.wineAndroidWsiLayerManifest(
            "/data/app/~~x/app.amphora-y/lib/arm64/libamphora_wsi.so");

    assertTrue(manifest.contains("\"name\": \"VK_LAYER_AMPHORA_wsi\""));
    assertTrue(
        manifest.contains(
            "\"library_path\": \"/data/app/~~x/app.amphora-y/lib/arm64/libamphora_wsi.so\""));
    assertTrue(manifest.contains("\"name\": \"VK_KHR_android_surface\", \"spec_version\": \"6\""));
    assertTrue(manifest.contains("\"disable_environment\""));
  }

  @Test
  public void wineandroidPathPrependsWsiHelperToLdPreload() {
    EnvVars envVars = new EnvVars();
    envVars.put(
        "LD_PRELOAD",
        "/imagefs/usr/lib/libandroid-sysvshm.so:/system/lib64/libjpeg.so");

    GuestProgramLauncherComponent.applyWineAndroidWsiHelperPreloadEnv(
        envVars, "/data/app/app.amphora/lib/arm64/libamphora_wsi.so");

    assertEquals(
        "/data/app/app.amphora/lib/arm64/libamphora_wsi.so:/imagefs/usr/lib/libandroid-sysvshm.so:/system/lib64/libjpeg.so",
        envVars.get("LD_PRELOAD"));
  }

  @Test
  public void defaultSyncEnvEnablesEsyncWithoutWinlatorFlag() {
    EnvVars envVars = new EnvVars();

    GuestProgramLauncherComponent.normalizeSyncEnvVars(envVars);

    assertEquals("1", envVars.get("WINEESYNC"));
    assertEquals("1", envVars.get("PROTON_NO_FSYNC"));
    assertFalse(envVars.has("WINEESYNC_WINLATOR"));
    assertFalse(envVars.has("WINEFSYNC"));
    assertFalse(envVars.has("PROTON_NO_ESYNC"));
  }

  @Test
  public void explicitWineesyncZeroDisablesEsync() {
    EnvVars envVars = new EnvVars();
    envVars.put("WINEESYNC", "0");
    envVars.put("WINEFSYNC", "1");
    envVars.put("WINEESYNC_WINLATOR", "1");

    GuestProgramLauncherComponent.normalizeSyncEnvVars(envVars);

    assertFalse(envVars.has("WINEESYNC"));
    assertEquals("1", envVars.get("PROTON_NO_ESYNC"));
    assertEquals("1", envVars.get("PROTON_NO_FSYNC"));
    assertFalse(envVars.has("WINEFSYNC"));
    assertFalse(envVars.has("WINEESYNC_WINLATOR"));
  }
}
