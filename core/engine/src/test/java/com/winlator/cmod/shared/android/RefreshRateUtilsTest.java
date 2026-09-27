package com.winlator.cmod.shared.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RefreshRateUtilsTest {
  @Test
  public void parsesRefreshRateLabelsAndDefaults() {
    assertEquals(120, RefreshRateUtils.parseRefreshRateLabel("120 Hz"));
    assertEquals(60, RefreshRateUtils.parseRefreshRateLabel(" 60 "));
    assertEquals(0, RefreshRateUtils.parseRefreshRateLabel("Default"));
    assertEquals(0, RefreshRateUtils.parseRefreshRateLabel(null));
  }

  @Test
  public void acceptsExactAndIntegerMultipleFrameCadence() {
    assertTrue(RefreshRateUtils.isFrameCadenceCompatible(60f, 60));
    assertTrue(RefreshRateUtils.isFrameCadenceCompatible(120f, 30));
    assertTrue(RefreshRateUtils.isFrameCadenceCompatible(59.94f, 30));
  }

  @Test
  public void rejectsIncompatibleOrDisabledFrameCadence() {
    assertFalse(RefreshRateUtils.isFrameCadenceCompatible(90f, 60));
    assertFalse(RefreshRateUtils.isFrameCadenceCompatible(60f, 90));
    assertFalse(RefreshRateUtils.isFrameCadenceCompatible(120f, 0));
  }

  @Test
  public void peakRefreshRateSettingParsesToHzOrUnset() {
    assertEquals(144f, RefreshRateUtils.normalizePeakRefreshRate("144.0"), 0f);
    assertEquals(0f, RefreshRateUtils.normalizePeakRefreshRate(null), 0f);
    assertEquals(0f, RefreshRateUtils.normalizePeakRefreshRate("Infinity"), 0f);
    assertEquals(0f, RefreshRateUtils.normalizePeakRefreshRate("0"), 0f);
    assertEquals(0f, RefreshRateUtils.normalizePeakRefreshRate("fast"), 0f);
  }

  @Test
  public void modesAboveTheUserPeakAreExcluded() {
    assertTrue(RefreshRateUtils.isWithinPeak(144f, 144f));
    assertTrue(RefreshRateUtils.isWithinPeak(60.000004f, 60f));
    assertFalse(RefreshRateUtils.isWithinPeak(165f, 144f));
    assertTrue(RefreshRateUtils.isWithinPeak(165f, 0f));
  }

  @Test
  public void explicitRefreshRateIsNotRedirectedByTheFpsLimit() {
    assertEquals(90, RefreshRateUtils.resolveFramePacedRefreshRate(null, 90, 60));
  }
}
