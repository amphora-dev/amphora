package app.amphora.gamesession.wineandroid

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log

/**
 * Debug-only exported relay for mid-session IME unicode/composing inject via adb.
 *
 * Cold start still uses [app.amphora.MainActivity] + `WINEANDROID`. Mid-session
 * `am start MainActivity` with Session on top only brings the task forward and
 * never hits [WineAndroidSessionActivity.onNewIntent]. Direct `am start` of
 * Session is SecurityException (exported=false). Same-UID [startActivity] of
 * the non-exported Session from this relay delivers onNewIntent.
 *
 * Clear composing with `--esn app.amphora.debug.IME_COMPOSING_TEXT` (Android
 * shell rejects `--es … ''`).
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
        val (unicode, composing) =
            WineAndroidDebugImeInject.relayForward(
                unicodeRaw = unicodeRaw,
                composingPresent = composingPresent,
                composingRaw = composingRaw,
            )
        if (unicode == null && composing == null) {
            Log.w(TAG, "No IME unicode/composing extras; finishing")
            finish()
            return
        }

        Log.i(
            TAG,
            "Forwarding to WineAndroidSessionActivity unicode=${unicode != null} " +
                "composingPresent=${composing != null} composingLen=${composing?.length}",
        )
        // Empty exePath is fine: Session onNewIntent only applies IME inject;
        // if no session exists, onCreate with empty exe is a no-op smoke fail.
        startActivity(
            WineAndroidSessionActivity.intent(
                context = this,
                exePath = "",
                debugImeUnicodeText = unicode,
                debugImeComposingText = composing,
            ),
        )
        finish()
    }

    companion object {
        private const val TAG = "WineAndroidImeRelay"
    }
}
