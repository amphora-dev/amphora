package com.winlator.cmod.feature.stores.steam.utils;

import com.winlator.cmod.feature.stores.steam.enums.Marker;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class MarkerUtils {

  public static final MarkerUtils INSTANCE = new MarkerUtils();

  private MarkerUtils() {}

  public boolean hasMarker(String dirPath, Marker marker) {
    return false;
  }

  public boolean addMarker(String dirPath, Marker marker) {
    return false;
  }

  public boolean removeMarker(String dirPath, Marker marker) {
    return false;
  }
}
