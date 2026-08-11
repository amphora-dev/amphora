package com.winlator.cmod.runtime.audio.alsaserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;
import com.winlator.cmod.runtime.wine.EnvVars;
import org.junit.Test;

public class ALSAClientTest {
  @Test
  public void mapsSupportedChannelCountsWithoutStereoDownmix() {
    assertEquals(AudioFormat.CHANNEL_OUT_MONO, ALSAClient.getChannelConfig(1));
    assertEquals(AudioFormat.CHANNEL_OUT_STEREO, ALSAClient.getChannelConfig(2));
    assertEquals(AudioFormat.CHANNEL_OUT_QUAD, ALSAClient.getChannelConfig(4));
    assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, ALSAClient.getChannelConfig(6));
    assertEquals(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, ALSAClient.getChannelConfig(8));
  }

  @Test
  public void rejectsUnknownChannelCounts() {
    assertEquals(AudioFormat.CHANNEL_INVALID, ALSAClient.getChannelConfig(0));
    assertEquals(AudioFormat.CHANNEL_INVALID, ALSAClient.getChannelConfig(3));
    assertEquals(AudioFormat.CHANNEL_INVALID, ALSAClient.getChannelConfig(7));
    assertEquals(AudioFormat.CHANNEL_INVALID, ALSAClient.getChannelConfig(255));
  }

  @Test
  public void prepareRejectsUnsupportedChannelCountBeforeCreatingAudioTrack() {
    ALSAClient client = validClient();
    client.setChannelCount(3);

    assertFalse(client.prepare());
  }

  @Test
  public void prepareRejectsZeroSampleRateBeforeCreatingAudioTrack() {
    ALSAClient client = validClient();
    client.setSampleRate(0);

    assertFalse(client.prepare());
  }

  @Test
  public void prepareRejectsZeroBufferBeforeCreatingAudioTrack() {
    ALSAClient client = validClient();
    client.setBufferSize(0);

    assertFalse(client.prepare());
  }

  @Test
  public void standardAlsaEnvironmentVariablesConfigureClientOptions() {
    EnvVars envVars =
        new EnvVars(
            "ALSA_LATENCY_MS=25 ALSA_VOLUME=0.75 ALSA_BASS_BOOST=0.5"
                + " ALSA_PERFORMANCE_MODE=low_latency");

    ALSAClient.Options options = ALSAClient.Options.fromEnvVars(envVars);

    assertEquals(25, options.latencyMillis);
    assertEquals(0.75f, options.volume, 0.0f);
    assertEquals(0.5f, options.bassBoost, 0.0f);
    assertEquals(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY, options.performanceMode);
  }

  @Test
  public void standardAlsaEnvironmentVariablesTakePrecedenceOverLegacyAliases() {
    EnvVars envVars =
        new EnvVars(
            "ALSA_LATENCY_MS=20 ANDROID_ALSA_LATENCY_MS=40 WINNATIVE_ALSA_LATENCY_MS=60"
                + " ALSA_VOLUME=0.5 ANDROID_ALSA_VOLUME=0.8 WINNATIVE_ALSA_VOLUME=0.9");

    ALSAClient.Options options = ALSAClient.Options.fromEnvVars(envVars);

    assertEquals(20, options.latencyMillis);
    assertEquals(0.5f, options.volume, 0.0f);
  }

  @Test
  public void userMuteSurvivesEnvironmentPauseAndResume() {
    ALSAClient.setMuted(true);
    ALSAClient.setEnvironmentPaused(true);
    ALSAClient.setEnvironmentPaused(false);

    assertTrue(ALSAClient.isOutputSuspended());

    ALSAClient.setMuted(false);
    assertFalse(ALSAClient.isOutputSuspended());
  }

  @Test
  public void masterVolumeIsClampedToTheUiRange() {
    ALSAClient.setMasterVolume(2.0f);
    assertEquals(1.0f, ALSAClient.getMasterVolume(), 0.0f);

    ALSAClient.setMasterVolume(-1.0f);
    assertEquals(0.0f, ALSAClient.getMasterVolume(), 0.0f);

    ALSAClient.setMasterVolume(1.0f);
  }

  private static ALSAClient validClient() {
    ALSAClient client = new ALSAClient();
    client.setDataType(ALSAClient.DataType.S16LE);
    client.setChannelCount(2);
    client.setSampleRate(48000);
    client.setBufferSize(256);
    return client;
  }
}
