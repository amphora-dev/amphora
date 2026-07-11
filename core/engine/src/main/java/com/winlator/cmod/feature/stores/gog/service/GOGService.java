package com.winlator.cmod.feature.stores.gog.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.winlator.cmod.feature.stores.gog.data.GOGGame;

/** Stub (RFC §7): not ported; minimal surface so the kernel compiles. Bodies are no-ops/default. */
public class GOGService extends Service {

  public static final Companion Companion = new Companion();

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  public static class Companion {
    public GOGGame getGOGGameOf(String gameId) {
      return null;
    }
  }
}
