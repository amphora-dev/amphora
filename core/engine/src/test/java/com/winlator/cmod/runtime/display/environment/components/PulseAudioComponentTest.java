package com.winlator.cmod.runtime.display.environment.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.winlator.cmod.runtime.wine.EnvVars;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PulseAudioComponentTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void parsesLatencyAndLowLatencyModeFromEnvironment() {
    EnvVars envVars = new EnvVars();
    envVars.put("PULSE_LATENCY_MSEC", "24");
    envVars.put("WINNATIVE_PULSE_AAUDIO_PERFORMANCE_MODE", "low_latency");

    PulseAudioComponent.Options options = PulseAudioComponent.Options.fromEnvVars(envVars);

    assertEquals(24, options.latencyMillis);
    assertEquals(PulseAudioComponent.Options.PERFORMANCE_MODE_LOW_LATENCY, options.performanceMode);
  }

  @Test
  public void clampsChannelsAndVolumeToSupportedRange() {
    EnvVars envVars = new EnvVars();
    envVars.put("WINNATIVE_PULSE_CHANNELS", "8");
    envVars.put("WINNATIVE_PULSE_VOLUME", "4");

    PulseAudioComponent.Options options = PulseAudioComponent.Options.fromEnvVars(envVars);

    assertEquals(2, options.channels);
    assertEquals(PulseAudioComponent.Options.MAX_VOLUME, options.volume, 0.0f);
  }

  @Test
  public void mapsPerformanceModesToAAudioConstants() {
    assertEquals(
        PulseAudioComponent.Options.PERFORMANCE_MODE_LOW_LATENCY,
        PulseAudioComponent.Options.fromEnvVars(new EnvVars()).performanceMode);
    assertEquals(
        0,
        PulseAudioComponent.performanceModeValue(
            PulseAudioComponent.Options.PERFORMANCE_MODE_NONE));
    assertEquals(
        1,
        PulseAudioComponent.performanceModeValue(
            PulseAudioComponent.Options.PERFORMANCE_MODE_LOW_LATENCY));
    assertEquals(
        2,
        PulseAudioComponent.performanceModeValue(
            PulseAudioComponent.Options.PERFORMANCE_MODE_POWER_SAVING));
  }

  @Test
  public void omitsChannelsFromAAudioSinkArguments() {
    PulseAudioComponent.Options options = new PulseAudioComponent.Options();
    String args = PulseAudioComponent.aaudioSinkArguments(options, 1.0f);
    assertTrue(args.contains("sink_name=AAudioSink"));
    assertTrue(args.contains("performance_mode=1"));
    assertTrue(args.contains("low_latency=true"));
    assertFalse(args.contains("channels="));
    assertFalse(args.contains("rate="));
  }

  @Test
  public void includesSampleRateOnlyWhenOverridden() {
    PulseAudioComponent.Options options = new PulseAudioComponent.Options();
    options.sampleRateOverridden = true;
    options.sampleRate = 44100;
    String args = PulseAudioComponent.aaudioSinkArguments(options, 0.5f);
    assertTrue(args.contains("rate=44100"));
    assertFalse(args.contains("channels="));
  }

  @Test
  public void detectsOnlyTheConfiguredAAudioSink() {
    assertTrue(
        PulseAudioComponent.containsSink(
            "0\tAAudioSink\tmodule-aaudio-sink.c\ts16le 2ch 48000Hz", "AAudioSink"));
    assertFalse(
        PulseAudioComponent.containsSink(
            "0\tAAudio_sink\tmodule-aaudio-sink.c\ts16le 2ch 48000Hz", "AAudioSink"));
    assertFalse(PulseAudioComponent.containsSink("", "AAudioSink"));
  }

  @Test
  public void supportsOnlyKnownPageSizesUpToFourKilobytes() {
    assertTrue(PulseAudioRuntimeSupport.isSupportedPageSize(4096));
    assertFalse(PulseAudioRuntimeSupport.isSupportedPageSize(16384));
    assertFalse(PulseAudioRuntimeSupport.isSupportedPageSize(-1));
  }

  @Test
  public void runtimeIsCurrentOnlyWithMatchedMarkerAndCompletePayload() throws Exception {
    File runtime = temporaryFolder.newFolder("pulseaudio");
    assertFalse(PulseAudioRuntimeSupport.isCurrent(runtime));

    touch(runtime, "pactl");
    touch(runtime, "modules/libprotocol-native.so");
    touch(runtime, "modules/module-aaudio-sink.so");
    touch(runtime, "modules/module-native-protocol-unix.so");
    Files.writeString(
        new File(runtime, ".asset-version").toPath(),
        PulseAudioRuntimeSupport.ASSET_VERSION,
        StandardCharsets.UTF_8);

    assertTrue(PulseAudioRuntimeSupport.isCurrent(runtime));

    Files.writeString(
        new File(runtime, ".asset-version").toPath(), "stale", StandardCharsets.UTF_8);
    assertFalse(PulseAudioRuntimeSupport.isCurrent(runtime));
  }

  private static void touch(File root, String relativePath) throws Exception {
    File file = new File(root, relativePath);
    assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
    assertTrue(file.createNewFile());
  }
}
