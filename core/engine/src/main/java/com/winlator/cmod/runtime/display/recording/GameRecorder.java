package com.winlator.cmod.runtime.display.recording;

import android.content.Context;
import android.view.Surface;
import java.nio.ByteBuffer;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class GameRecorder {

  public GameRecorder(Context context) {}

  public static GameRecorder active() {
    return null;
  }

  public boolean isRecording() {
    return false;
  }

  public synchronized Surface start(
      int width, int height, int fps, int orientationHint, int bitRate) {
    return null;
  }

  public Surface getInputSurface() {
    return null;
  }

  public void onPcm(ByteBuffer data, int sampleRate, int channelCount, int pcmEncoding) {}

  public synchronized void stop() {}
}
