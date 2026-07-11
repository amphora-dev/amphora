package com.winlator.cmod.runtime.display;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class PerformanceHudState {

  private PerformanceHudState() {}

  public static void setVisible(boolean v) {}

  public static void updateEnabled(boolean[] enabled) {}

  public static void updateValues(
      float fps,
      float frametimeMs,
      int gpuLoad,
      int cpuPercent,
      int ramPercent,
      float batteryWatts,
      int tempC,
      String renderer) {}

  public static final class Snapshot {
    public boolean[] enabled;
    public float fps;
    public float frametimeMs;
    public int gpuLoad;
    public int cpuPercent;
    public int ramPercent;
    public float batteryWatts;
    public int tempC;
    public String renderer;

    public Snapshot() {}
  }
}
