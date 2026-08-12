package com.winlator.cmod.runtime.display.renderer;

/** Immutable snapshot of native compositor GPU and presentation timing. */
public final class RendererPerformanceTelemetry {
  public static final RendererPerformanceTelemetry UNAVAILABLE =
      new RendererPerformanceTelemetry(
          Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, false, 0, 0);

  public final double gpuRenderMs;
  public final double displayFps;
  public final double presentIntervalMs;
  public final double presentMarginMs;
  public final double refreshCycleMs;
  public final boolean gpuTimingSupported;
  public final boolean displayTimingSupported;
  public final long gpuSampleCount;
  public final long displaySampleCount;

  private RendererPerformanceTelemetry(
      double gpuRenderMs,
      double displayFps,
      double presentIntervalMs,
      double presentMarginMs,
      double refreshCycleMs,
      boolean gpuTimingSupported,
      boolean displayTimingSupported,
      long gpuSampleCount,
      long displaySampleCount) {
    this.gpuRenderMs = finiteOrNaN(gpuRenderMs);
    this.displayFps = finiteOrNaN(displayFps);
    this.presentIntervalMs = finiteOrNaN(presentIntervalMs);
    this.presentMarginMs = finiteOrNaN(presentMarginMs);
    this.refreshCycleMs = finiteOrNaN(refreshCycleMs);
    this.gpuTimingSupported = gpuTimingSupported;
    this.displayTimingSupported = displayTimingSupported;
    this.gpuSampleCount = Math.max(0, gpuSampleCount);
    this.displaySampleCount = Math.max(0, displaySampleCount);
  }

  static RendererPerformanceTelemetry fromNative(double[] values) {
    if (values == null || values.length < 8) return UNAVAILABLE;
    int flags = (int) values[5];
    return new RendererPerformanceTelemetry(
        values[0],
        values[1],
        values[2],
        values[3],
        values[4],
        (flags & 1) != 0,
        (flags & 2) != 0,
        (long) values[6],
        (long) values[7]);
  }

  private static double finiteOrNaN(double value) {
    return Double.isFinite(value) && value >= 0.0 ? value : Double.NaN;
  }
}
