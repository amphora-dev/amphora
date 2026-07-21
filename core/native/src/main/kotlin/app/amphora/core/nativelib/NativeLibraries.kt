package app.amphora.core.nativelib

/**
 * Native library names loaded via `System.loadLibrary` (RFC §7 / D5).
 *
 * `libwinlator.so` exposes the `Java_com_winlator_cmod_*` JNI exports (winlator C/CXX
 * sources + statically linked adrenotools; zstd/xz via FetchContent for archive
 * extract). `libfakeinput.so` is an LD_PRELOAD evdev input shim with no JNI.
 *
 * Remote-download JNI (`nativeDownloadFile` / `nativeFetchContentLength`) remains
 * stubbed — MVP provisions content from APK assets (BundledContentSource), not curl.
 * JNI binding classes live in `:core:engine` (native never depends upward).
 *
 * Note: this package is `app.amphora.core.nativelib` because `native` is a Java
 * keyword and cannot be an AGP namespace segment. Ported JNI classes keep
 * `com.winlator.cmod` so C exports resolve with zero edits.
 */
object NativeLibraries {
    const val WINLATOR = "winlator"
    const val FAKEINPUT = "fakeinput"
}
