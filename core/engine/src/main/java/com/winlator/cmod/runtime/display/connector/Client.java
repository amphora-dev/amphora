package com.winlator.cmod.runtime.display.connector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Client {
  public final ClientSocket clientSocket;
  private final XConnectorEpoll connector;
  private XInputStream inputStream;
  private XOutputStream outputStream;
  private Object tag;
  protected Thread pollThread;
  protected int shutdownFd = -1;
  protected volatile boolean connected;

  public Client(XConnectorEpoll connector, ClientSocket clientSocket) {
    this.connector = connector;
    this.clientSocket = clientSocket;
  }

  public void createIOStreams() {
    if (inputStream != null || outputStream != null) return;
    inputStream = new XInputStream(clientSocket, connector.getInitialInputBufferCapacity());
    outputStream = new XOutputStream(clientSocket, connector.getInitialOutputBufferCapacity());
    inputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
    outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
  }

  public XInputStream getInputStream() {
    return inputStream;
  }

  public XOutputStream getOutputStream() {
    return outputStream;
  }

  public void releaseIOStreams() {
    if (inputStream != null) {
      inputStream.release();
      inputStream = null;
    }
    if (outputStream != null) {
      outputStream.release();
      outputStream = null;
    }
  }

  public Object getTag() {
    return tag;
  }

  public void setTag(Object tag) {
    this.tag = tag;
  }

  protected void requestShutdown() {
    requestShutdown(shutdownFd);
  }

  /** Write to a specific shutdown eventfd (used after ownership was claimed). */
  protected void requestShutdown(int eventFd) {
    if (eventFd < 0) return;
    try {
      ByteBuffer data = ByteBuffer.allocateDirect(8);
      data.asLongBuffer().put(1);
      (new ClientSocket(eventFd)).write(data);
      XInputStream.freeDirectBuffer(data);
    } catch (IOException e) {
    }
  }
}
