package com.winlator.cmod.runtime.display.xserver.extensions;

import com.winlator.cmod.runtime.display.connector.XInputStream;
import com.winlator.cmod.runtime.display.connector.XOutputStream;
import com.winlator.cmod.runtime.display.xserver.XClient;
import com.winlator.cmod.runtime.display.xserver.errors.XRequestError;
import java.io.IOException;

public interface Extension {
  String getName();

  byte getMajorOpcode();

  byte getFirstErrorId();

  byte getFirstEventId();

  int getNumEvents();

  int getNumErrors();

  void setFirstEventId(byte id);

  void setFirstErrorId(byte id);

  void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException, XRequestError;

  /**
   * Drop any state this extension keeps on behalf of {@code client}, which has
   * disconnected.
   *
   * Extensions that key state by XID must implement this. XIDs are only unique
   * per client, and {@link com.winlator.cmod.runtime.display.xserver.ResourceIDs}
   * recycles a client's id base as soon as it disconnects — so the next client
   * generates the exact same ids. State left behind under an old id then belongs
   * to a dead client, and the new client's first request on that id looks like a
   * conflict rather than a fresh allocation.
   *
   * Resource cleanup (windows, pixmaps) already happens through
   * {@link com.winlator.cmod.runtime.display.xserver.XResourceManager.OnResourceLifecycleListener};
   * this is for state an extension holds that outlives the resources it names.
   */
  default void onClientDisconnected(XClient client) {}
}
