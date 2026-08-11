package com.winlator.cmod.runtime.wine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WineAudioDriverTest {
  @Test
  public void usesAlsaForTheAvailableBackend() {
    assertEquals("alsa", WineUtils.wineAudioDriverName("alsa"));
  }

  @Test
  public void mapsPulseAudioConfigurationToWinePulseDriver() {
    assertEquals("pulse", WineUtils.wineAudioDriverName("pulseaudio"));
  }

  @Test
  public void rejectsUnknownAudioDrivers() {
    assertNull(WineUtils.wineAudioDriverName("unknown"));
    assertNull(WineUtils.wineAudioDriverName(null));
  }
}
