package app.amphora.gamesession.wineandroid

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.UnixSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Listens on `AMPHORA_WINEANDROID_SOCK` (filesystem AF_UNIX) and dispatches
 * ioctl-shaped frames to [WineAndroidHostBridge].
 *
 * Host→unix frames ([WineAndroidProtocol.HOST_SURFACE_CHANGED] /
 * [WineAndroidProtocol.HOST_DESKTOP_CHANGED]) may include SCM_RIGHTS fds via
 * [LocalSocket.setFileDescriptorsForSend].
 */
class WineAndroidHostSocket(private val bridge: WineAndroidHostBridge) {
    private val running = AtomicBoolean(false)
    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile private var peer: LocalSocket? = null

    /** Last known desktop metrics — resent when a peer connects. */
    @Volatile private var desktopWidth: Int = 0

    @Volatile private var desktopHeight: Int = 0

    @Volatile private var desktopScale: Float = 1f

    fun start(socketFile: File) {
        if (!running.compareAndSet(false, true)) return
        socketFile.parentFile?.mkdirs()
        if (socketFile.exists()) socketFile.delete()

        val serverSocket =
            try {
                bindFilesystem(socketFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind ${socketFile.absolutePath}", e)
                running.set(false)
                return
            }
        server = serverSocket
        Log.i(TAG, "listening on ${socketFile.absolutePath} (filesystem AF_UNIX)")

        acceptThread =
            thread(name = "WineAndroidHostSocket", isDaemon = true) {
                try {
                    while (running.get() && !Thread.currentThread().isInterrupted) {
                        val client =
                            try {
                                serverSocket.accept()
                            } catch (e: IOException) {
                                if (!running.get() || Thread.currentThread().isInterrupted) break
                                Log.w(TAG, "accept failed", e)
                                break
                            }
                        Log.i(TAG, "peer connected")
                        peer = client
                        try {
                            maybeSendCachedDesktop(client)
                            serve(client)
                        } catch (e: IOException) {
                            Log.i(TAG, "peer disconnected: ${e.message}")
                        } finally {
                            if (peer === client) peer = null
                            try {
                                client.close()
                            } catch (_: IOException) {
                            }
                        }
                    }
                } finally {
                    Log.i(TAG, "accept loop exited")
                }
            }
    }

    fun updateDesktopMetrics(width: Int, height: Int, scale: Float = 1f) {
        if (width <= 0 || height <= 0) return
        desktopWidth = width
        desktopHeight = height
        desktopScale = scale
        sendDesktopChanged(width, height, scale)
    }

    fun sendDesktopChanged(width: Int, height: Int, scale: Float = 1f) {
        val socket = peer ?: run {
            Log.i(TAG, "desktop_changed ${width}x$height queued (no peer yet)")
            return
        }
        val frame = WineAndroidProtocol.encodeDesktopChanged(width, height, scale)
        try {
            synchronized(socket) {
                socket.outputStream.write(frame)
                socket.outputStream.flush()
            }
            Log.i(TAG, "HOST_DESKTOP_CHANGED ${width}x$height scale=$scale (${frame.size} bytes)")
        } catch (e: IOException) {
            Log.w(TAG, "failed to write DESKTOP_CHANGED", e)
        }
    }

    /**
     * @param wineBufferFd when [ready], wine end of the per-HWND buffer socketpair
     *   (host keeps the other end for [WineAndroidNative.nativeStartBufferServe]).
     */
    fun sendSurfaceChanged(
        hwnd: Int,
        opengl: Boolean,
        ready: Boolean,
        width: Int = 0,
        height: Int = 0,
        wineBufferFd: FileDescriptor? = null,
    ) {
        val socket = peer ?: run {
            Log.i(TAG, "surface_changed hwnd=$hwnd queued (no peer yet)")
            return
        }
        val frame = WineAndroidProtocol.encodeSurfaceChanged(hwnd, opengl, ready, width, height)
        try {
            synchronized(socket) {
                if (ready && wineBufferFd != null) {
                    socket.setFileDescriptorsForSend(arrayOf(wineBufferFd))
                }
                socket.outputStream.write(frame)
                socket.outputStream.flush()
            }
            Log.i(
                TAG,
                "HOST_SURFACE_CHANGED hwnd=$hwnd opengl=$opengl ready=$ready " +
                    "size=${width}x$height fd=${wineBufferFd != null} (${frame.size} bytes)",
            )
        } catch (e: IOException) {
            Log.w(TAG, "failed to write SURFACE_CHANGED", e)
        }
    }

    fun close() {
        running.set(false)
        acceptThread?.interrupt()
        try {
            peer?.close()
        } catch (_: IOException) {
        }
        peer = null
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        acceptThread = null
    }

    private fun maybeSendCachedDesktop(client: LocalSocket) {
        val w = desktopWidth
        val h = desktopHeight
        if (w <= 0 || h <= 0) return
        val frame = WineAndroidProtocol.encodeDesktopChanged(w, h, desktopScale)
        try {
            synchronized(client) {
                client.outputStream.write(frame)
                client.outputStream.flush()
            }
            Log.i(TAG, "resent cached HOST_DESKTOP_CHANGED ${w}x$h on connect")
        } catch (e: IOException) {
            Log.w(TAG, "failed to resend desktop metrics", e)
        }
    }

    private fun serve(client: LocalSocket) {
        val input = DataInputStream(client.inputStream)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val header = ByteArray(8)
            try {
                input.readFully(header)
            } catch (e: IOException) {
                throw e
            }
            val hdr = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val opcode = hdr.int
            val nbytes = hdr.int
            if (nbytes < 0 || nbytes > MAX_PAYLOAD) {
                throw IOException("invalid payload size $nbytes for opcode $opcode")
            }
            val payload = ByteArray(nbytes)
            if (nbytes > 0) input.readFully(payload)
            val msg =
                try {
                    WineAndroidProtocol.decode(opcode, payload)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "decode failed opcode=$opcode: ${e.message}")
                    null
                }
            if (msg == null) {
                Log.w(TAG, "ignored opcode=$opcode nbytes=$nbytes (buffer/cursor ops stay on unix)")
                continue
            }
            dispatch(msg)
        }
    }

    private fun dispatch(msg: WineAndroidProtocol.HostMessage) {
        when (msg) {
            is WineAndroidProtocol.HostMessage.CreateWindow ->
                bridge.createWindow(msg.hwnd, msg.opengl, msg.parent, msg.scale, msg.pid)
            is WineAndroidProtocol.HostMessage.DestroyWindow ->
                bridge.destroyWindow(msg.hwnd)
            is WineAndroidProtocol.HostMessage.WindowPosChanged ->
                bridge.windowPosChanged(
                    msg.hwnd,
                    msg.flags,
                    msg.insertAfter,
                    msg.owner,
                    msg.style,
                    msg.windowRect,
                    msg.clientRect,
                    msg.visibleRect,
                )
            is WineAndroidProtocol.HostMessage.SetParent ->
                bridge.setParent(msg.hwnd, msg.parent, msg.scale, msg.pid)
        }
    }

    private companion object {
        const val TAG = "WineAndroidHostSocket"
        const val MAX_PAYLOAD = 64 * 1024

        fun bindFilesystem(socketFile: File): LocalServerSocket {
            val fd: FileDescriptor =
                Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
            try {
                Os.bind(fd, UnixSocketAddress.createFileSystem(socketFile.absolutePath))
                Os.listen(fd, 4)
            } catch (t: Throwable) {
                try {
                    Os.close(fd)
                } catch (_: Exception) {
                }
                throw t
            }
            return LocalServerSocket(fd)
        }
    }
}

/** Helper to build a socketpair for one HWND's buffer channel. */
internal fun createBufferSocketPair(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
    val pair = ParcelFileDescriptor.createSocketPair()
    return pair[0] to pair[1]
}
