package com.winlator.cmod.runtime.audio.alsaserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.media.AudioFormat;
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

  private static ALSAClient validClient() {
    ALSAClient client = new ALSAClient();
    client.setDataType(ALSAClient.DataType.S16LE);
    client.setChannelCount(2);
    client.setSampleRate(48000);
    client.setBufferSize(256);
    return client;
  }
}
