package com.winlator.cmod.runtime.display.environment.components;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NetworkHelperTest {
  @Test
  public void formatsArbitraryIpv4Prefixes() {
    assertEquals("255.255.240.0", NetworkHelper.formatNetmask(20, false));
    assertEquals("255.255.255.128", NetworkHelper.formatNetmask(25, false));
    assertEquals("0.0.0.0", NetworkHelper.formatNetmask(0, false));
  }

  @Test
  public void formatsArbitraryIpv6Prefixes() {
    assertEquals(
        "ffff:ffff:ffff:ffff:0:0:0:0", NetworkHelper.formatNetmask(64, true));
    assertEquals(
        "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ff00",
        NetworkHelper.formatNetmask(120, true));
  }

  @Test
  public void rejectsPrefixesOutsideTheAddressWidth() {
    assertEquals("", NetworkHelper.formatNetmask(-1, false));
    assertEquals("", NetworkHelper.formatNetmask(33, false));
    assertEquals("", NetworkHelper.formatNetmask(129, true));
  }
}
