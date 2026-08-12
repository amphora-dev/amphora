package com.winlator.cmod.runtime.display.xserver.requests;

import static com.winlator.cmod.runtime.display.xserver.Keyboard.KEYSYMS_PER_KEYCODE;
import static com.winlator.cmod.runtime.display.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.runtime.display.connector.XInputStream;
import com.winlator.cmod.runtime.display.connector.XOutputStream;
import com.winlator.cmod.runtime.display.connector.XStreamLock;
import com.winlator.cmod.runtime.display.xserver.Keyboard;
import com.winlator.cmod.runtime.display.xserver.XClient;
import com.winlator.cmod.runtime.display.xserver.errors.XRequestError;
import java.io.IOException;

public abstract class KeyboardRequests {
  public static void getKeyboardMapping(
      XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException, XRequestError {
    int firstKeycode = inputStream.readUnsignedByte();
    int count = inputStream.readUnsignedByte();
    inputStream.skip(2);

    try (XStreamLock lock = outputStream.lock()) {
      outputStream.writeByte(RESPONSE_CODE_SUCCESS);
      outputStream.writeByte(KEYSYMS_PER_KEYCODE);
      outputStream.writeShort(client.getSequenceNumber());
      outputStream.writeInt(count * KEYSYMS_PER_KEYCODE);
      outputStream.writePad(24);

      int index = (firstKeycode - Keyboard.MIN_KEYCODE) * KEYSYMS_PER_KEYCODE;
      for (int keycode = 0; keycode < count; keycode++) {
        outputStream.writeInt(client.xServer.keyboard.keysyms[index++]);
        outputStream.writeInt(client.xServer.keyboard.keysyms[index++]);
      }
    }
  }

  public static void getModifierMapping(
      XClient client, XInputStream inputStream, XOutputStream outputStream)
      throws IOException, XRequestError {
    try (XStreamLock lock = outputStream.lock()) {
      outputStream.writeByte(RESPONSE_CODE_SUCCESS);
      outputStream.writeByte((byte) 1);
      outputStream.writeShort(client.getSequenceNumber());
      outputStream.writeInt(2);
      outputStream.writePad(24);
      outputStream.writePad(8);
    }
  }
}
