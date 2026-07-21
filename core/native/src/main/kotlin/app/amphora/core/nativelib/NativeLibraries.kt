package app.amphora.core.nativelib

/**
 * Native library names loaded via `System.loadLibrary` (RFC §7 / D5).
 *
 * `libwinlator.so` exposes the `Java_com_winlator_cmod_*` JNI exports (winlator C/CXX
 * sources + statically linked adrenotools; zstd/xz via FetchContent for archive
 * extract). Remote-download JNI remains stubbed — MVP provisions content from APK
 * assets (BundledContentSource). JNI binding classes live in `:core:engine`.
 *
 * `libfakeinput.so` is no longer built: amphora routes input via XServer inject,
 * not the LD_PRELOAD evdev shim. The former `fakeinput.cpp` source was removed
 * with the MVP trim; reintroduce from WinNative when native gamepad returns.
 *
 * Note: this package is `app.amphora.core.nativelib` because `native` is a Java
 * keyword and cannot be an AGP namespace segment. Ported JNI classes keep
 * `com.winlator.cmod` so C exports resolve with zero edits.
 */
object NativeLibraries {
    const val WINLATOR = "winlator"
}
