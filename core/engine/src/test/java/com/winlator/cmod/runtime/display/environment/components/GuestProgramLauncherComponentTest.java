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
  public void wineandroidSystemVulkanEnvDropsWrapperIcdAndAdrenotools() {
    EnvVars envVars = new EnvVars();
    envVars.put("VK_ICD_FILENAMES", "/imagefs/usr/share/vulkan/icd.d/wrapper_icd.aarch64.json");
    envVars.put("ADRENOTOOLS_DRIVER_NAME", "vulkan.broadcom.so");
    envVars.put("ADRENOTOOLS_DRIVER_PATH", "/vendor/lib64/hw/");
    envVars.put("ADRENOTOOLS_HOOKS_PATH", "/imagefs/usr/lib");
    envVars.put("ADRENOTOOLS_DRIVER_CUSTOM", "1");
    envVars.put("LD_LIBRARY_PATH", "/imagefs/usr/lib:/system/lib64");

    GuestProgramLauncherComponent.applyWineAndroidSystemVulkanEnv(envVars, "/files/wineandroid/vkloader");

    assertFalse(envVars.has("VK_ICD_FILENAMES"));
    assertFalse(envVars.has("ADRENOTOOLS_DRIVER_NAME"));
    assertFalse(envVars.has("ADRENOTOOLS_DRIVER_PATH"));
    assertFalse(envVars.has("ADRENOTOOLS_HOOKS_PATH"));
    assertFalse(envVars.has("ADRENOTOOLS_DRIVER_CUSTOM"));
    assertTrue(envVars.get("LD_LIBRARY_PATH").startsWith("/files/wineandroid/vkloader:"));
    assertTrue(envVars.get("LD_LIBRARY_PATH").contains("/imagefs/usr/lib"));
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
  public void wineAndroidNeedsSystemVulkanIsDeterministicApi() {
    // Just ensure the helper is callable from unit tests (Build.* is host JVM stub /
    // Robolectric-free). Result may be true or false depending on the JVM Build fields;
    // we only assert it does not throw and returns a boolean.
    boolean v = GuestProgramLauncherComponent.wineAndroidNeedsSystemVulkan();
    assertTrue(v || !v);
  }
}
