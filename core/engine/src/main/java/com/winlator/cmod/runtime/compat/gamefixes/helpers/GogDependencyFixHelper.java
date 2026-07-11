package com.winlator.cmod.runtime.compat.gamefixes.helpers;

import java.util.List;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class GogDependencyFixHelper {

  public static final GogDependencyFixHelper INSTANCE = new GogDependencyFixHelper();

  private GogDependencyFixHelper() {}

  public void ensureDependencies(
      String gameId, List<String> dependencyIds, String installPath) {}
}
