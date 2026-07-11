package com.winlator.cmod.feature.stores.steam.utils;

import android.content.Context;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public final class PrefManager {

  public static final PrefManager INSTANCE = new PrefManager();

  private PrefManager() {}

  public void install(Context context) {}

  public void init(Context context) {}

  public String getUsername() {
    return null;
  }

  public void setUsername(String value) {}

  public String getRefreshToken() {
    return null;
  }

  public void setRefreshToken(String value) {}

  public String getAccessToken() {
    return null;
  }

  public void setAccessToken(String value) {}

  public long getSteamUserSteamId64() {
    return 0L;
  }

  public void setSteamUserSteamId64(long value) {}

  public int getSteamUserAccountId() {
    return 0;
  }

  public void setSteamUserAccountId(int value) {}

  public boolean getWnHybridMode() {
    return false;
  }

  public void setWnHybridMode(boolean value) {}

  public boolean getWnPlanW() {
    return false;
  }

  public void setWnPlanW(boolean value) {}

  public int getCellId() {
    return 0;
  }

  public void setCellId(int value) {}

  public boolean getCellIdManuallySet() {
    return false;
  }

  public void setCellIdManuallySet(boolean value) {}

  public int getLastPICSChangeNumber() {
    return 0;
  }

  public void setLastPICSChangeNumber(int value) {}

  public String getSteamUserName() {
    return null;
  }

  public void setSteamUserName(String value) {}

  public String getSteamUserAvatarHash() {
    return null;
  }

  public void setSteamUserAvatarHash(String value) {}

  public String getFriendsSnapshotJson() {
    return null;
  }

  public void setFriendsSnapshotJson(String value) {}

  public int getPersonaState() {
    return 0;
  }

  public void setPersonaState(int value) {}

  public String getExternalStoragePath() {
    return null;
  }

  public void setExternalStoragePath(String value) {}

  public boolean getUseExternalStorage() {
    return false;
  }

  public void setUseExternalStorage(boolean value) {}

  public String getContainerLanguage() {
    return null;
  }

  public void setContainerLanguage(String value) {}

  public int getDownloadSpeed() {
    return 0;
  }

  public void setDownloadSpeed(int value) {}

  public long getClientId() {
    return 0L;
  }

  public void setClientId(long value) {}

  public String getLibraryLayoutMode() {
    return null;
  }

  public void setLibraryLayoutMode(String value) {}

  public String getLibraryStoreVisible() {
    return null;
  }

  public void setLibraryStoreVisible(String value) {}

  public String getLibraryContentFilters() {
    return null;
  }

  public void setLibraryContentFilters(String value) {}

  public boolean getLibraryImmersiveMode() {
    return false;
  }

  public void setLibraryImmersiveMode(boolean value) {}

  public boolean getLibraryImmersiveBlur() {
    return false;
  }

  public void setLibraryImmersiveBlur(boolean value) {}

  public boolean getEnableSteamLogs() {
    return false;
  }

  public void setEnableSteamLogs(boolean value) {}

  public boolean getUseSingleDownloadFolder() {
    return false;
  }

  public void setUseSingleDownloadFolder(boolean value) {}

  public String getDefaultDownloadFolder() {
    return null;
  }

  public void setDefaultDownloadFolder(String value) {}

  public String getSteamDownloadFolder() {
    return null;
  }

  public void setSteamDownloadFolder(String value) {}

  public String getEpicDownloadFolder() {
    return null;
  }

  public void setEpicDownloadFolder(String value) {}

  public String getGogDownloadFolder() {
    return null;
  }

  public void setGogDownloadFolder(String value) {}

  public boolean getChatNotificationsEnabled() {
    return false;
  }

  public void setChatNotificationsEnabled(boolean value) {}

  public boolean getChatHeadsEnabled() {
    return false;
  }

  public void setChatHeadsEnabled(boolean value) {}

  public boolean getChatInGameEnabled() {
    return false;
  }

  public void setChatInGameEnabled(boolean value) {}

  public boolean getChatHeadsAutoHide() {
    return false;
  }

  public void setChatHeadsAutoHide(boolean value) {}

  public boolean getChatStayRunningOnExit() {
    return false;
  }

  public void setChatStayRunningOnExit(boolean value) {}

  public void clearAuthTokens() {}

  public void clearPreferences() {}
}
