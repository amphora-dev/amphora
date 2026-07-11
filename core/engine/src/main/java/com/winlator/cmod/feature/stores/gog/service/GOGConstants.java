package com.winlator.cmod.feature.stores.gog.service;

import android.content.Context;
import java.util.List;
import java.util.Map;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class GOGConstants {

  public static final GOGConstants INSTANCE = new GOGConstants();

  private GOGConstants() {}

  public static final String GOG_BASE_API_URL = "https://api.gog.com";
  public static final String GOG_AUTH_URL = "https://auth.gog.com";
  public static final String GOG_EMBED_URL = "https://embed.gog.com";
  public static final String GOG_GAMESDB_URL = "https://gamesdb.gog.com";
  public static final String GOG_CLIENT_ID = "46899977096215655";
  public static final String GOG_CLIENT_SECRET =
      "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9";
  public static final String GOG_REDIRECT_URI = "https://embed.gog.com/on_login_success?origin=client";
  public static final String GOG_FALLBACK_DOWNLOAD_LANGUAGE = "english";

  public String getGOG_AUTH_LOGIN_URL() {
    return null;
  }

  public String getInternalGOGGamesPath() {
    return null;
  }

  public String getExternalGOGGamesPath() {
    return null;
  }

  public String getDefaultGOGGamesPath() {
    return null;
  }

  public Map<String, String> getGOG_DEPENDENCY_INSTALLED_PATH() {
    return null;
  }

  public void init(Context context) {}

  public List<String> containerLanguageToGogCodes(String containerLanguage) {
    return null;
  }

  public String getSanitizedGameFolderName(String gameTitle) {
    return null;
  }

  public String getGameInstallPath(String gameTitle) {
    return null;
  }
}
