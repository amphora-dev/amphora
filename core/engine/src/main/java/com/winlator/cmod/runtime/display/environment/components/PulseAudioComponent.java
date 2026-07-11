package com.winlator.cmod.runtime.display.environment.components;

import com.winlator.cmod.runtime.display.connector.UnixSocketConfig;
import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.runtime.wine.EnvVars;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class PulseAudioComponent extends EnvironmentComponent {

  public PulseAudioComponent(UnixSocketConfig socketConfig) {}

  public PulseAudioComponent(UnixSocketConfig socketConfig, Options options) {}

  @Override
  public void start() {}

  @Override
  public void stop() {}

  public void suspend() {}

  public void resume() {}

  public boolean isServerRunning() {
    return false;
  }

  public static class Options {
    public static final int DEFAULT_LATENCY_MILLIS = 40;
    public static final int DEFAULT_FRAGMENT_MILLIS = 10;
    public static final int DEFAULT_SAMPLE_RATE = 48000;
    public static final int DEFAULT_ALTERNATE_SAMPLE_RATE = 44100;
    public static final int DEFAULT_CHANNELS = 2;
    public static final float DEFAULT_VOLUME = 1.0f;
    public static final float MAX_VOLUME = 2.0f;
    public static final String PERFORMANCE_MODE_NONE = "none";
    public static final String PERFORMANCE_MODE_POWER_SAVING = "power_saving";
    public static final String PERFORMANCE_MODE_LOW_LATENCY = "low_latency";

    public int latencyMillis = DEFAULT_LATENCY_MILLIS;
    public int fragmentMillis = DEFAULT_FRAGMENT_MILLIS;
    public int sampleRate = DEFAULT_SAMPLE_RATE;
    public int alternateSampleRate = DEFAULT_ALTERNATE_SAMPLE_RATE;
    public int channels = DEFAULT_CHANNELS;
    public float volume = DEFAULT_VOLUME;
    public String performanceMode = PERFORMANCE_MODE_NONE;
    public boolean sampleRateOverridden = false;
    public boolean alternateSampleRateOverridden = false;

    public static Options fromEnvVars(EnvVars envVars) {
      return null;
    }
  }
}
