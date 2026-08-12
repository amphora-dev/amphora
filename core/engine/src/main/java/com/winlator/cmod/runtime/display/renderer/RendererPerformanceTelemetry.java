package com.winlator.cmod.runtime.display.renderer;

/** Immutable snapshot of native compositor GPU and presentation timing. */
public final class RendererPerformanceTelemetry {
  public static final RendererPerformanceTelemetry UNAVAILABLE =
      new RendererPerformanceTelemetry(
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          false,
          false,
          0,
          0,
          Double.NaN,
          Double.NaN,
          0,
          Double.NaN,
          Double.NaN);

  public final double gpuRenderMs;
  public final double displayFps;
  public final double presentIntervalMs;
  public final double presentMarginMs;
  public final double refreshCycleMs;
  public final boolean gpuTimingSupported;
  public final boolean displayTimingSupported;
  public final long gpuSampleCount;
  public final long displaySampleCount;
  public final double compositorPresentFps;
  public final double compositorPresentIntervalMs;
  public final long compositorPresentSampleCount;
  public final double compositorFrameP95Ms;
  public final double compositorOnePercentLowFps;

  private RendererPerformanceTelemetry(
      double gpuRenderMs,
      double displayFps,
      double presentIntervalMs,
      double presentMarginMs,
      double refreshCycleMs,
      boolean gpuTimingSupported,
      boolean displayTimingSupported,
      long gpuSampleCount,
      long displaySampleCount,
      double compositorPresentFps,
      double compositorPresentIntervalMs,
      long compositorPresentSampleCount,
      double compositorFrameP95Ms,
      double compositorOnePercentLowFps) {
    this.gpuRenderMs = finiteOrNaN(gpuRenderMs);
    this.displayFps = finiteOrNaN(displayFps);
    this.presentIntervalMs = finiteOrNaN(presentIntervalMs);
    this.presentMarginMs = finiteOrNaN(presentMarginMs);
    this.refreshCycleMs = finiteOrNaN(refreshCycleMs);
    this.gpuTimingSupported = gpuTimingSupported;
    this.displayTimingSupported = displayTimingSupported;
    this.gpuSampleCount = Math.max(0, gpuSampleCount);
    this.displaySampleCount = Math.max(0, displaySampleCount);
    this.compositorPresentFps = finiteOrNaN(compositorPresentFps);
    this.compositorPresentIntervalMs = finiteOrNaN(compositorPresentIntervalMs);
    this.compositorPresentSampleCount = Math.max(0, compositorPresentSampleCount);
    this.compositorFrameP95Ms = finiteOrNaN(compositorFrameP95Ms);
    this.compositorOnePercentLowFps = finiteOrNaN(compositorOnePercentLowFps);
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
        (long) values[7],
        values.length > 8 ? values[8] : Double.NaN,
        values.length > 9 ? values[9] : Double.NaN,
        values.length > 10 ? (long) values[10] : 0,
        values.length > 11 ? values[11] : Double.NaN,
        values.length > 12 ? values[12] : Double.NaN);
  }

  private static double finiteOrNaN(double value) {
    return Double.isFinite(value) && value >= 0.0 ? value : Double.NaN;
  }
}
