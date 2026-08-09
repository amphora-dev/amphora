package com.winlator.cmod.runtime.display.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.winlator.cmod.runtime.display.renderer.RenderCallback;
import com.winlator.cmod.runtime.display.renderer.VulkanRenderer;
import com.winlator.cmod.runtime.display.xserver.XServer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link TextureView} that drives a {@link VulkanRenderer} on a dedicated render
 * thread. Originally a {@code SurfaceView} (WinNative XSDA), converted to
 * TextureView because Compose {@code AndroidView} does not allocate the
 * SurfaceView sub-window surface (surfaceCreated never fires) — TextureView
 * renders as a regular view so its surface arrives reliably under Compose.
 * Public API preserved: {@link #queueEvent(Runnable)}, {@link #requestRender()},
 * {@link #setRenderMode(int)}, {@link #onResume()}, {@link #onPause()}, {@link #getRenderer()}.
 */
@SuppressLint("ViewConstructor")
public class XServerSurfaceView extends TextureView implements TextureView.SurfaceTextureListener {
    public static final int RENDERMODE_WHEN_DIRTY  = 0;
    public static final int RENDERMODE_CONTINUOUSLY = 1;
    private static final long TRANSIENT_FRAME_INTERVAL_NS = 1_000_000_000L / 120L;

    private final VulkanRenderer renderer;
    private final AtomicBoolean firstGuestFrameRendered = new AtomicBoolean(false);
    private volatile Runnable firstGuestFrameRenderedListener;

    private final Object renderLock = new Object();
    private final Deque<Runnable> eventQueue = new ArrayDeque<>();
    private Thread renderThread;
    // Outgoing render thread finishing teardown; the next surfaceCreated joins it first so a stale destroy() can't free the handle the new surface re-attaches to.
    private Thread retiringRenderThread;
    private volatile boolean running;
    private volatile boolean renderRequested;
    private volatile boolean transientRenderRequested;
    private volatile boolean paused;
    private volatile boolean surfaceReady;
    private volatile long transientRenderUntilNs;
    private long nextContinuousFrameNs;
    private int renderMode = RENDERMODE_WHEN_DIRTY;

    private volatile int width;
    private volatile int height;

    public XServerSurfaceView(Context context, XServer xServer) {
        super(context);
        setOpaque(true);
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderer = new VulkanRenderer(this, xServer);
        setSurfaceTextureListener(this);
    }

    public VulkanRenderer getRenderer() {
        return renderer;
    }

    public void setOnFirstGuestFrameRenderedListener(Runnable listener) {
        firstGuestFrameRenderedListener = listener;
        if (listener != null && firstGuestFrameRendered.get()) post(listener);
    }

    /** Called by {@link VulkanRenderer} after the first guest-presented frame is submitted. */
    public void notifyFirstGuestFrameRendered() {
        if (!firstGuestFrameRendered.compareAndSet(false, true)) return;
        Runnable listener = firstGuestFrameRenderedListener;
        if (listener != null) post(listener);
    }

    public void queueEvent(Runnable r) {
        if (r == null) return;
        synchronized (renderLock) {
            eventQueue.add(r);
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void requestRender() {
        synchronized (renderLock) {
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void requestTransientRender(long durationMs) {
        long untilNs = System.nanoTime() + Math.max(1L, durationMs) * 1_000_000L;
        synchronized (renderLock) {
            if (untilNs > transientRenderUntilNs) transientRenderUntilNs = untilNs;
            transientRenderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void setRenderMode(int mode) {
        if (mode != RENDERMODE_WHEN_DIRTY && mode != RENDERMODE_CONTINUOUSLY) return;
        synchronized (renderLock) {
            renderMode = mode;
            if (mode == RENDERMODE_CONTINUOUSLY) {
                renderRequested = true;
                renderLock.notifyAll();
            }
        }
    }

    public int getRenderMode() {
        return renderMode;
    }

    public void onResume() {
        synchronized (renderLock) {
            paused = false;
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    public void onPause() {
        synchronized (renderLock) {
            paused = true;
            renderLock.notifyAll();
        }
    }

    // --- TextureView.SurfaceTextureListener ---------------------------------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int w, int h) {
        // Let any retiring render thread finish freeing the renderer before attaching the new surface.
        joinRetiringRenderThread();
        synchronized (renderLock) {
            surfaceReady = false;
            width = 0;
            height = 0;
        }
        renderer.attachSurface(new Surface(surfaceTexture));
        if (w > 0 && h > 0) {
            renderer.notifySurfaceChanged(w, h);
            synchronized (renderLock) {
                width = w;
                height = h;
                eventQueue.add(() -> renderer.onSurfaceChanged(w, h));
                surfaceReady = true;
                renderRequested = true;
                renderLock.notifyAll();
            }
        }
        startRenderThreadIfNeeded();
    }

    private void joinRetiringRenderThread() {
        Thread t;
        synchronized (renderLock) {
            t = retiringRenderThread;
            retiringRenderThread = null;
        }
        if (t != null && t != Thread.currentThread() && t.isAlive()) {
            try { t.join(3000); } catch (InterruptedException ignore) {}
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int w, int h) {
        if (w <= 0 || h <= 0) {
            synchronized (renderLock) {
                surfaceReady = false;
                width = 0;
                height = 0;
                renderLock.notifyAll();
            }
            return;
        }

        renderer.notifySurfaceChanged(w, h);
        synchronized (renderLock) {
            width = w;
            height = h;
            eventQueue.add(() -> renderer.onSurfaceChanged(w, h));
            surfaceReady = true;
            renderRequested = true;
            renderLock.notifyAll();
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        synchronized (renderLock) {
            surfaceReady = false;
            width = 0;
            height = 0;
            renderLock.notifyAll();
        }
        // Run the render thread one more iteration so it sees surfaceReady=false and exits.
        stopRenderThread();
        renderer.detachSurface();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // Frame produced by the guest; the render thread pulls and presents. No-op here.
    }

    // --- Render thread -------------------------------------------------------

    private void startRenderThreadIfNeeded() {
        if (renderThread != null && renderThread.isAlive()) return;
        running = true;
        renderThread = new Thread(this::renderLoop, "VkRenderer");
        renderThread.start();
    }

    private void stopRenderThread() {
        synchronized (renderLock) {
            running = false;
            renderLock.notifyAll();
            if (renderThread != null) retiringRenderThread = renderThread;
            renderThread = null;
        }
    }

    /**
     * Quiesces this view and waits for its render thread to run the synchronous native
     * renderer teardown. Call from a worker thread, never the Android main thread.
     *
     * <p>The dedicated session process is the final isolation boundary, but normal exits
     * still close Vulkan cleanly so driver caches and kernel objects are not abandoned.
     */
    public boolean closeAndJoin(long timeoutMs) {
        final Thread thread;
        synchronized (renderLock) {
            paused = true;
            surfaceReady = false;
            width = 0;
            height = 0;
            running = false;
            renderLock.notifyAll();
            if (renderThread != null) {
                retiringRenderThread = renderThread;
                renderThread = null;
            }
            thread = retiringRenderThread;
        }

        renderer.detachSurface();
        if (thread != null && thread != Thread.currentThread() && thread.isAlive()) {
            try {
                thread.join(Math.max(1L, timeoutMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (thread != null && thread.isAlive()) return false;

        // No render thread means no onSurfaceDestroyed callback will arrive. destroy() is
        // idempotent, so this also safely confirms an already-completed teardown.
        renderer.destroy();
        synchronized (renderLock) {
            if (retiringRenderThread == thread) retiringRenderThread = null;
            eventQueue.clear();
        }
        return true;
    }

    private void renderLoop() {
        renderer.onSurfaceCreated();
        if (width > 0 && height > 0) renderer.onSurfaceChanged(width, height);

        while (true) {
            Runnable event = null;
            boolean draw = false;
            synchronized (renderLock) {
                while (true) {
                    if (!running) break;
                    if (paused || !surfaceReady) {
                        nextContinuousFrameNs = 0;
                        try { renderLock.wait(50); } catch (InterruptedException ignore) {}
                        continue;
                    }

                    long now = System.nanoTime();
                    boolean transientActive = transientRenderUntilNs > now;

                    if (!eventQueue.isEmpty()) {
                        event = eventQueue.poll();
                        break;
                    }

                    if (renderRequested) {
                        draw = true;
                        renderRequested = false;
                        transientRenderRequested = false;
                        if (!transientActive) nextContinuousFrameNs = 0;
                        break;
                    }

                    if (renderMode == RENDERMODE_CONTINUOUSLY) {
                        draw = true;
                        transientRenderRequested = false;
                        nextContinuousFrameNs = 0;
                        break;
                    }

                    if (transientRenderRequested) {
                        draw = true;
                        transientRenderRequested = false;
                        nextContinuousFrameNs = now + TRANSIENT_FRAME_INTERVAL_NS;
                        break;
                    }

                    if (transientActive) {
                        if (nextContinuousFrameNs == 0 || now >= nextContinuousFrameNs) {
                            draw = true;
                            nextContinuousFrameNs = now + TRANSIENT_FRAME_INTERVAL_NS;
                            break;
                        }
                        waitNanosLocked(nextContinuousFrameNs - now);
                        continue;
                    }

                    nextContinuousFrameNs = 0;
                    try { renderLock.wait(); } catch (InterruptedException ignore) {}
                }
            }
            if (!running) break;
            if (event != null) {
                try {
                    event.run();
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    android.util.Log.e("AMP_SURFACE", "render event failed", t);
                }
            } else if (draw) {
                try {
                    renderer.onDrawFrame();
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    android.util.Log.e("AMP_SURFACE", "onDrawFrame failed", t);
                }
            }
        }
        renderer.onSurfaceDestroyed();
    }

    private void waitNanosLocked(long nanos) {
        if (nanos <= 0) return;
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        try { renderLock.wait(millis, extraNanos); } catch (InterruptedException ignore) {}
    }

    // ---- Convenience accessors used by VulkanRenderer ----------------------

    public int getSurfaceWidth() { return width; }
    public int getSurfaceHeight() { return height; }
}
