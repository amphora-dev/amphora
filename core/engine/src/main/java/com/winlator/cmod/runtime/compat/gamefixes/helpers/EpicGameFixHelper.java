package com.winlator.cmod.runtime.compat.gamefixes.helpers;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class EpicGameFixHelper {

  public static final EpicGameFixHelper INSTANCE = new EpicGameFixHelper();

  private EpicGameFixHelper() {}

  public String getCatalogIdForAppId(String appIdStr) {
    return null;
  }

  public String getInstallPathForCatalog(String catalogId) {
    return null;
  }
}
