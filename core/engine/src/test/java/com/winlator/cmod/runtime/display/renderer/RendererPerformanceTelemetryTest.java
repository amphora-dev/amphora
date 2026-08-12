package com.winlator.cmod.runtime.display.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RendererPerformanceTelemetryTest {
  @Test
  public void parsesSupportedNativeTelemetry() {
    RendererPerformanceTelemetry telemetry =
        RendererPerformanceTelemetry.fromNative(
            new double[] {
              3.25, 59.94, 16.7, 2.1, 16.666, 3, 42, 40, 58.5, 17.09, 55, 19.2, 48.0
            });

    assertEquals(3.25, telemetry.gpuRenderMs, 0.001);
    assertEquals(59.94, telemetry.displayFps, 0.001);
    assertTrue(telemetry.gpuTimingSupported);
    assertTrue(telemetry.displayTimingSupported);
    assertEquals(42, telemetry.gpuSampleCount);
    assertEquals(40, telemetry.displaySampleCount);
    assertEquals(58.5, telemetry.compositorPresentFps, 0.001);
    assertEquals(17.09, telemetry.compositorPresentIntervalMs, 0.001);
    assertEquals(55, telemetry.compositorPresentSampleCount);
    assertEquals(19.2, telemetry.compositorFrameP95Ms, 0.001);
    assertEquals(48.0, telemetry.compositorOnePercentLowFps, 0.001);
  }

  @Test
  public void rejectsMalformedAndNonFiniteSnapshots() {
    assertFalse(RendererPerformanceTelemetry.fromNative(null).gpuTimingSupported);

    RendererPerformanceTelemetry telemetry =
        RendererPerformanceTelemetry.fromNative(
            new double[] {Double.POSITIVE_INFINITY, -1, Double.NaN, 0, 0, 1, -4, -2});

    assertTrue(Double.isNaN(telemetry.gpuRenderMs));
    assertTrue(Double.isNaN(telemetry.displayFps));
    assertTrue(telemetry.gpuTimingSupported);
    assertFalse(telemetry.displayTimingSupported);
    assertEquals(0, telemetry.gpuSampleCount);
    assertEquals(0, telemetry.displaySampleCount);
    assertTrue(Double.isNaN(telemetry.compositorPresentFps));
    assertEquals(0, telemetry.compositorPresentSampleCount);
  }
}
