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
}
