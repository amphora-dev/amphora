package com.winlator.cmod.runtime.audio.alsaserver;

import static org.junit.Assert.assertEquals;

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
}
