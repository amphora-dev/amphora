package app.amphora.sdk.internal

import android.util.Log

/** Logcat with one tag, so `adb logcat -s amphora` shows the SDK's side of things. */
internal object AmphoraLog {
    private const val TAG = "amphora"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
