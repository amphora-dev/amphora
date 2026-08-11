package com.winlator.cmod.runtime.display.environment.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
}
