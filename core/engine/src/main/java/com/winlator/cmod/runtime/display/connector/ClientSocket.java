package com.winlator.cmod.runtime.display.connector;

import androidx.annotation.Keep;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientSocket {
  public final int fd;
  private final ArrayDeque<Integer> ancillaryFds = new ArrayDeque<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  static {
    System.loadLibrary("winlator");
  }

  public ClientSocket(int fd) {
    this.fd = fd;
  }

  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Close the client socket once. Safe to call from a teardown thread while
   * another thread is blocked in {@code recvmsg}/{@code read} — closing the fd
   * unblocks the native call so poll threads can exit without a join timeout.
   */
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    closeAncillaryFds();
    XConnectorEpoll.closeFd(fd);
  }

  public boolean hasAncillaryFds() {
    return !ancillaryFds.isEmpty();
  }

  public int getAncillaryFd() {
    return hasAncillaryFds() ? ancillaryFds.poll() : -1;
  }

  public void closeAncillaryFds() {
    while (!ancillaryFds.isEmpty()) {
      int ancillaryFd = ancillaryFds.poll();
      if (ancillaryFd >= 0) XConnectorEpoll.closeFd(ancillaryFd);
    }
  }

  @Keep
  public void addAncillaryFd(int ancillaryFd) {
    ancillaryFds.add(ancillaryFd);
  }

  public int read(ByteBuffer data) throws IOException {
    if (closed.get()) return -1;
    int position = data.position();
    int bytesRead = read(fd, data, position, data.remaining());
    if (bytesRead > 0) {
      data.position(position + bytesRead);
      return bytesRead;
    } else if (bytesRead == 0) {
      return -1;
    } else throw new IOException("Failed to read data.");
  }

  public void write(ByteBuffer data) throws IOException {
    if (closed.get()) throw new IOException("Socket closed.");
    int bytesWritten = write(fd, data, data.limit());
    if (bytesWritten >= 0) {
      data.position(bytesWritten);
    } else throw new IOException("Failed to write data.");
  }

  public int recvAncillaryMsg(ByteBuffer data) throws IOException {
    if (closed.get()) return -1;
    int position = data.position();
    int bytesRead = recvAncillaryMsg(fd, data, position, data.remaining());
    if (bytesRead > 0) {
      data.position(position + bytesRead);
      return bytesRead;
    } else if (bytesRead == 0) {
      return -1;
    } else throw new IOException("Failed to receive ancillary messages.");
  }

  public void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException {
    if (closed.get()) throw new IOException("Socket closed.");
    int bytesSent = sendAncillaryMsg(fd, data, data.limit(), ancillaryFd);
    if (bytesSent >= 0) {
      data.position(bytesSent);
    } else throw new IOException("Failed to send ancillary messages.");
  }

  private native int read(int fd, ByteBuffer data, int offset, int length);

  private native int write(int fd, ByteBuffer data, int length);

  private native int recvAncillaryMsg(int clientFd, ByteBuffer data, int offset, int length);

  private native int sendAncillaryMsg(int clientFd, ByteBuffer data, int length, int ancillaryFd);
}
