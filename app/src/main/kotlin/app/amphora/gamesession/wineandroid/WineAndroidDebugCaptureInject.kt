package app.amphora.gamesession.wineandroid

import android.content.Intent

/**
 * Debug-only SetCapture inject for HA262 shell smoke (no title-bar drag drama).
 *
 * Honored only when the process is FLAG_DEBUGGABLE.
 * [WineAndroidSessionActivity] applies via cold start / onNewIntent (relay).
 *
 * - [EXTRA_CAPTURE_HWND]: `--ei … N` → [WineAndroidDesktop.setCapture].
 *   Absent → no-op. `0` releases. [SENTINEL_DESKTOP] (`-1`) resolves to the
 *   desktop hwnd once known (defers if not ready).
 *
 * Does not change guest IOCTL_SET_CAPTURE behavior; host-only smoke helper.
 */
object WineAndroidDebugCaptureInject {
    const val EXTRA_CAPTURE_HWND = "app.amphora.debug.CAPTURE_HWND"

    /** Resolve to [WineAndroidDesktop] desktop hwnd when available. */
    const val SENTINEL_DESKTOP = -1

    /**
     * null = absent / not debuggable (no-op); otherwise the requested hwnd
     * (incl. 0 = release, [SENTINEL_DESKTOP] = use desktop when ready).
     */
    fun captureHwndIfDebuggable(present: Boolean, value: Int, debuggable: Boolean): Int? {
        if (!debuggable || !present) return null
        return value
    }

    fun captureHwndFromIntent(intent: Intent?, debuggable: Boolean): Int? {
        if (intent == null || !intent.hasExtra(EXTRA_CAPTURE_HWND)) {
            return captureHwndIfDebuggable(
                present = false,
                value = 0,
                debuggable = debuggable,
            )
        }
        return captureHwndIfDebuggable(
            present = true,
            value = intent.getIntExtra(EXTRA_CAPTURE_HWND, 0),
            debuggable = debuggable,
        )
    }

    /**
     * Map requested inject onto a concrete capture hwnd.
     *
     * @return null = defer (sentinel and desktop not ready yet);
     *   otherwise hwnd to pass to [WineAndroidDesktop.setCapture] (0 = release).
     */
    fun resolveCaptureTarget(requested: Int, desktopHwnd: Int): Int? {
        if (requested == SENTINEL_DESKTOP) {
            return if (desktopHwnd != 0) desktopHwnd else null
        }
        return requested
    }

    /**
     * Mid-session relay: null = omit; non-null = put CAPTURE_HWND extra.
     */
    fun relayForward(capturePresent: Boolean, captureValue: Int): Int? = if (capturePresent) captureValue else null
}
