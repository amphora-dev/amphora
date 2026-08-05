package com.winlator.cmod.runtime.display.connector;

import android.util.SparseArray;
import androidx.annotation.Keep;
import java.io.IOException;
import java.nio.ByteBuffer;

public class XConnectorEpoll implements Runnable {
  private final ConnectionHandler connectionHandler;
  private final RequestHandler requestHandler;
  private final int epollFd;
  private final int serverFd;
  private final int shutdownFd;
  private Thread epollThread;
  private boolean running = false;
  private boolean multithreadedClients = false;
  private boolean canReceiveAncillaryMessages = false;
  private int initialInputBufferCapacity = 4096;
  private int initialOutputBufferCapacity = 4096;
  private final SparseArray<Client> connectedClients = new SparseArray<>();

  static {
    System.loadLibrary("winlator");
  }

  public XConnectorEpoll(
      UnixSocketConfig socketConfig,
      ConnectionHandler connectionHandler,
      RequestHandler requestHandler) {
    this.connectionHandler = connectionHandler;
    this.requestHandler = requestHandler;

    serverFd = createAFUnixSocket(socketConfig.path);
    if (serverFd < 0) {
      throw new RuntimeException("Failed to create an AF_UNIX socket.");
    }

    epollFd = createEpollFd();
    if (epollFd < 0) {
      closeFd(serverFd);
      throw new RuntimeException("Failed to create epoll fd.");
    }

    if (!addFdToEpoll(epollFd, serverFd)) {
      closeFd(serverFd);
      closeFd(epollFd);
      throw new RuntimeException("Failed to add server fd to epoll.");
    }

    shutdownFd = createEventFd();
    if (!addFdToEpoll(epollFd, shutdownFd)) {
      closeFd(serverFd);
      closeFd(shutdownFd);
      closeFd(epollFd);
      throw new RuntimeException("Failed to add shutdown fd to epoll.");
    }

    epollThread = new Thread(this);
  }

  public synchronized void start() {
    if (running || epollThread == null) return;
    running = true;
    epollThread.start();
  }

  public synchronized void stop() {
    if (!running || epollThread == null) return;
    running = false;
    requestShutdown();
    // Unblock client poll threads stuck in recvmsg/read before joining epoll.
    // (epoll itself wakes via shutdownFd; client recv does not.)
    wakeBlockedClientIo();
    joinOrGiveUp(epollThread, JOIN_TIMEOUT_MS);
    epollThread = null;
  }

  /** Close every client socket so native recvmsg/read returns and poll loops exit. */
  private void wakeBlockedClientIo() {
    synchronized (connectedClients) {
      for (int i = 0; i < connectedClients.size(); i++) {
        Client client = connectedClients.valueAt(i);
        client.connected = false;
        if (multithreadedClients) {
          client.requestShutdown();
        }
        // Idempotent: full killConnection may also close this socket moments later.
        client.clientSocket.close();
      }
    }
  }

  @Override
  public void run() {
    while (running && doEpollIndefinitely(epollFd, serverFd, !multithreadedClients))
      ;
    shutdown();
  }

  @Keep
  private void handleNewConnection(int fd) {
    final Client client = new Client(this, new ClientSocket(fd));
    client.connected = true;
    if (multithreadedClients) {
      client.shutdownFd = createEventFd();
      final int clientShutdownFd = client.shutdownFd;
      client.pollThread =
          new Thread(
              () -> {
                connectionHandler.handleNewConnection(client);
                // Use the captured eventfd: killConnection claims client.shutdownFd under
                // lock and sets it to -1 before close; the poll loop must keep watching
                // the original descriptor until it exits.
                while (client.connected
                    && waitForSocketRead(client.clientSocket.fd, clientShutdownFd)) {
                  handleExistingConnection(client.clientSocket.fd);
                }
              });
      client.pollThread.start();
    } else connectionHandler.handleNewConnection(client);
    synchronized (connectedClients) {
      connectedClients.put(fd, client);
    }
  }

  @Keep
  private void handleExistingConnection(int fd) {
    Client client;
    synchronized (connectedClients) {
      client = connectedClients.get(fd);
    }
    if (client == null) return;

    XInputStream inputStream = client.getInputStream();
    try {
      if (inputStream == null) {
        // Streams not ready yet, or already released during teardown.
        if (!client.connected) return;
        return;
      }
      if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
        int activePosition = 0;
        while (running && client.connected && requestHandler.handleRequest(client))
          activePosition = inputStream.getActivePosition();
        // inputStream may have been released mid-loop during teardown.
        if (client.getInputStream() == inputStream) {
          inputStream.setActivePosition(activePosition);
        }
      } else killConnection(client);
    } catch (IOException e) {
      killConnection(client);
    } catch (RuntimeException e) {
      // Teardown races (null streams) must not crash the Android process.
      if (client.connected) killConnection(client);
    }
  }

  public Client getClient(int fd) {
    synchronized (connectedClients) {
      return connectedClients.get(fd);
    }
  }

  /**
   * Tear down a client connection exactly once.
   *
   * <p>Closing the client socket unblocks any poll thread stuck in {@code recvmsg}/{@code read}.
   * Concurrent callers (teardown {@link #wakeBlockedClientIo()}, epoll/poll after hangup, and
   * {@link #shutdown()}) used to race on {@code shutdownFd}: the first {@code close} freed the
   * number, the kernel reused it for a {@code unique_fd}-tagged descriptor, and the second
   * {@code close} aborted under fdsan. Ownership of {@code shutdownFd} is therefore claimed
   * under {@code connectedClients} before any native close.
   */
  public void killConnection(Client client) {
    final int shutdownFdToClose;
    final Thread pollThreadToJoin;
    final boolean alreadyRemoved;
    synchronized (connectedClients) {
      alreadyRemoved = connectedClients.get(client.clientSocket.fd) != client;
      if (!alreadyRemoved) {
        connectedClients.remove(client.clientSocket.fd);
      }
      client.connected = false;
      shutdownFdToClose = client.shutdownFd;
      client.shutdownFd = -1;
      pollThreadToJoin = client.pollThread;
      // Clear before join so a concurrent killConnection cannot join the same thread twice.
      if (pollThreadToJoin != null && Thread.currentThread() != pollThreadToJoin) {
        client.pollThread = null;
      }
    }

    if (alreadyRemoved) {
      // Another killConnection owns full cleanup; still ensure the socket is closed so any
      // peer blocked in recv wakes up.
      client.clientSocket.close();
      return;
    }

    connectionHandler.handleConnectionShutdown(client);

    // Close the client fd before joining: poll threads block in recvmsg inside
    // handleExistingConnection, where shutdown eventfd alone cannot wake them.
    client.clientSocket.close();

    if (multithreadedClients) {
      if (pollThreadToJoin != null && Thread.currentThread() != pollThreadToJoin) {
        client.requestShutdown(shutdownFdToClose);
        joinOrGiveUp(pollThreadToJoin, JOIN_TIMEOUT_MS);
      }
      if (shutdownFdToClose >= 0) {
        closeFd(shutdownFdToClose);
      }
    } else {
      // Socket may already be closed (wakeBlockedClientIo); DEL on a closed fd is fine.
      removeFdFromEpoll(epollFd, client.clientSocket.fd);
    }

    client.releaseIOStreams();
  }

  /**
   * Bounded join as a safety net. Prefer waking via {@link ClientSocket#close()} so
   * threads exit before the timeout; interrupt alone does not unblock native recvmsg.
   */
  private static void joinOrGiveUp(Thread thread, long timeoutMs) {
    if (thread == null) return;
    final long deadline = System.currentTimeMillis() + timeoutMs;
    while (thread.isAlive()) {
      final long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        thread.interrupt();
        return;
      }
      try {
        thread.join(remaining);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static final long JOIN_TIMEOUT_MS = 2_000L;

  private void shutdown() {
    while (true) {
      Client client;
      synchronized (connectedClients) {
        if (connectedClients.size() == 0) break;
        client = connectedClients.valueAt(connectedClients.size() - 1);
      }
      killConnection(client);
    }

    removeFdFromEpoll(epollFd, serverFd);
    removeFdFromEpoll(epollFd, shutdownFd);
    closeFd(serverFd);
    closeFd(shutdownFd);
    closeFd(epollFd);
  }

  public int getInitialInputBufferCapacity() {
    return initialInputBufferCapacity;
  }

  public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
    this.initialInputBufferCapacity = initialInputBufferCapacity;
  }

  public int getInitialOutputBufferCapacity() {
    return initialOutputBufferCapacity;
  }

  public void setInitialOutputBufferCapacity(int initialOutputBufferCapacity) {
    this.initialOutputBufferCapacity = initialOutputBufferCapacity;
  }

  public boolean isMultithreadedClients() {
    return multithreadedClients;
  }

  public void setMultithreadedClients(boolean multithreadedClients) {
    this.multithreadedClients = multithreadedClients;
  }

  public boolean isCanReceiveAncillaryMessages() {
    return canReceiveAncillaryMessages;
  }

  public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
    this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
  }

  private void requestShutdown() {
    try {
      ByteBuffer data = ByteBuffer.allocateDirect(8);
      data.asLongBuffer().put(1);
      (new ClientSocket(shutdownFd)).write(data);
      XInputStream.freeDirectBuffer(data);
    } catch (IOException e) {
    }
  }

  public static native void closeFd(int fd);

  private native int createEpollFd();

  private native int createEventFd();

  private native boolean doEpollIndefinitely(int epollFd, int serverFd, boolean addClientToEpoll);

  private native boolean addFdToEpoll(int epollFd, int fd);

  private native void removeFdFromEpoll(int epollFd, int fd);

  private native boolean waitForSocketRead(int clientFd, int shutdownFd);

  private native int createAFUnixSocket(String path);
}
