package app.amphora.gamesession.wineandroid

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log

/**
 * Debug-only exported relay for mid-session IME / capture inject via adb.
 *
 * Cold start still uses [app.amphora.MainActivity] + `WINEANDROID`. Mid-session
 * `am start MainActivity` with Session on top only brings the task forward and
 * never hits [WineAndroidSessionActivity.onNewIntent]. Direct `am start` of
 * Session is SecurityException (exported=false). Same-UID [startActivity] of
 * the non-exported Session from this relay delivers onNewIntent.
 *
 * Clear composing with `--esn app.amphora.debug.IME_COMPOSING_TEXT` (Android
 * shell rejects `--es … ''`). Capture: `--ei app.amphora.debug.CAPTURE_HWND N`
 * (`0` release; `-1` = desktop hwnd). Z-order eye-check:
 * `--ez app.amphora.debug.DUMP_ZORDER true` and/or
 * `--ei app.amphora.debug.ZORDER_TOP_HWND N`.
 */
class WineAndroidDebugImeRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) {
            finish()
            return
        }

        val unicodeRaw =
            intent.getStringExtra(WineAndroidDebugImeInject.EXTRA_IME_UNICODE_TEXT)
        val composingPresent =
            intent.hasExtra(WineAndroidDebugImeInject.EXTRA_IME_COMPOSING_TEXT)
        val composingRaw =
            if (composingPresent) {
                intent.getStringExtra(WineAndroidDebugImeInject.EXTRA_IME_COMPOSING_TEXT)
            } else {
                null
            }
        val imeShowPresent =
            intent.hasExtra(WineAndroidDebugImeInject.EXTRA_IME_SHOW)
        val imeShowValue =
            intent.getBooleanExtra(WineAndroidDebugImeInject.EXTRA_IME_SHOW, false)
        val (unicode, composing, showIme) =
            WineAndroidDebugImeInject.relayForward(
                unicodeRaw = unicodeRaw,
                composingPresent = composingPresent,
                composingRaw = composingRaw,
                imeShowPresent = imeShowPresent,
                imeShowValue = imeShowValue,
            )
        val capturePresent =
            intent.hasExtra(WineAndroidDebugCaptureInject.EXTRA_CAPTURE_HWND)
        val captureValue =
            intent.getIntExtra(WineAndroidDebugCaptureInject.EXTRA_CAPTURE_HWND, 0)
        val captureHwnd =
            WineAndroidDebugCaptureInject.relayForward(
                capturePresent = capturePresent,
                captureValue = captureValue,
            )
        val dumpPresent =
            intent.hasExtra(WineAndroidDebugZOrderInject.EXTRA_DUMP_ZORDER)
        val dumpValue =
            intent.getBooleanExtra(WineAndroidDebugZOrderInject.EXTRA_DUMP_ZORDER, false)
        val dumpZ =
            WineAndroidDebugZOrderInject.relayDump(
                dumpPresent = dumpPresent,
                dumpValue = dumpValue,
            )
        val zTopPresent =
            intent.hasExtra(WineAndroidDebugZOrderInject.EXTRA_ZORDER_TOP_HWND)
        val zTopValue =
            intent.getIntExtra(WineAndroidDebugZOrderInject.EXTRA_ZORDER_TOP_HWND, 0)
        val zTop =
            WineAndroidDebugZOrderInject.relayZOrderTop(
                topPresent = zTopPresent,
                topValue = zTopValue,
            )
        if (
            unicode == null &&
            composing == null &&
            showIme == null &&
            captureHwnd == null &&
            dumpZ == null &&
            zTop == null
        ) {
            Log.w(TAG, "No IME/capture/zorder extras; finishing")
            finish()
            return
        }

        Log.i(
            TAG,
            "Forwarding to WineAndroidSessionActivity unicode=${unicode != null} " +
                "composingPresent=${composing != null} composingLen=${composing?.length} " +
                "imeShow=$showIme captureHwnd=$captureHwnd dumpZ=$dumpZ zTop=$zTop",
        )
        // Empty exePath is fine: Session onNewIntent only applies debug inject;
        // if no session exists, onCreate with empty exe is a no-op smoke fail.
        startActivity(
            WineAndroidSessionActivity.intent(
                context = this,
                exePath = "",
                debugImeUnicodeText = unicode,
                debugImeComposingText = composing,
                debugImeShow = showIme,
                debugCaptureHwnd = captureHwnd,
                debugDumpZOrder = dumpZ,
                debugZOrderTopHwnd = zTop,
            ),
        )
        finish()
    }

    companion object {
        private const val TAG = "WineAndroidImeRelay"
    }
}
