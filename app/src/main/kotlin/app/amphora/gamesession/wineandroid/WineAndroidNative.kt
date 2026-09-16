package app.amphora.gamesession.wineandroid

import android.view.Surface

/**
 * JNI for the upstream wineandroid host IPC server.
 *
 * Binds abstract `\0\Device\WineAndroid` (SEQPACKET) and serves device.c ioctls.
 * Surfaces stay in `:session`; [nativeRegisterSurface] attaches ANativeWindow
 * for buffer ioctls and writes SURFACE_CHANGED on the desktop event pipe.
 * [nativeSendMotionEvent] packs MOTION_EVENT + INPUT_MOUSE onto the same pipe
 * (upstream WineView → wine_motion_event).
 * [nativeSendKeyboardEvent] packs KEYBOARD_EVENT + INPUT_KEYBOARD (upstream
 * WineView.dispatchKeyEvent → wine_keyboard_event). Hardware KEYCODE_* path.
 * [nativeSendUnicodeChar] packs KEYEVENTF_UNICODE on the same pipe for IME
 * commits KeyCharacterMap cannot map (CJK). Not IMM32/TSF.
 */
object WineAndroidNative {
    init {
        System.loadLibrary("winlator")
    }

    external fun nativeStartServer(callbacks: WineAndroidIpcCallbacks): Boolean

    external fun nativeStopServer()

    external fun nativeRegisterSurface(hwnd: Int, surface: Surface, opengl: Boolean): Boolean

    external fun nativeUnregisterSurface(hwnd: Int, opengl: Boolean)

    external fun nativeNotifyDesktopChanged(width: Int, height: Int)

    external fun nativeNotifyConfigChanged(dpi: Int)

    /**
     * Pack and write a wineandroid MOTION_EVENT (guest desktop px).
     * @return false if action unsupported or event pipe not ready.
     */
    external fun nativeSendMotionEvent(
        hwnd: Int,
        action: Int,
        x: Int,
        y: Int,
        state: Int,
        vscroll: Int,
    ): Boolean

    /**
     * Pack and write a wineandroid KEYBOARD_EVENT (AKEYCODE → vkey/scancode).
     * @return false if keycode is unmapped or event pipe not ready.
     */
    external fun nativeSendKeyboardEvent(
        hwnd: Int,
        action: Int,
        keycode: Int,
        state: Int,
    ): Boolean

    /**
     * Pack KEYEVENTF_UNICODE KEYBOARD_EVENT(s) for [codePoint] (UTF-16 units,
     * down+up each). Supplementary planes → surrogate pair (Windows IME pattern).
     * @return false if code point invalid or event pipe not ready.
     */
    external fun nativeSendUnicodeChar(hwnd: Int, codePoint: Int): Boolean
}
