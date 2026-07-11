package app.amphora.core.nativelib

/**
 * Native library names loaded via `System.loadLibrary` (RFC §7 / D5).
 *
 * `libwinlator.so` exposes the 48 `Java_com_winlator_cmod_*` JNI exports
 * (10 C/CXX sources ported verbatim from WinNative `cpp/winlator/` -- the
 * `com.winlator.cmod` package prefix is preserved so the C is zero-edit).
 * `libfakeinput.so` is an LD_PRELOAD evdev input shim with no JNI. adrenotools
 * is statically linked into `libwinlator.so` (Turnip driver loading).
 *
 * Trim (RFC §7 optional裁剪): `native_content_io.cpp` is NOT ported (MVP does
 * no native download), which drops the curl + zstd + xz dependencies -- that
 * file was their sole consumer. The Java JNI binding classes (VulkanRenderer /
 * Drawable / SysVSharedMemory / ...) are deferred to `:core:engine` (P1): they
 * import the runtime kernel, so architecture (`native` never depends upward)
 * forbids them from living in `:core:native`; the `.so` needs them only at
 * runtime, not at compile time.
 *
 * Note: this package is `app.amphora.core.nativelib` because `native` is a Java
 * keyword and cannot be an AGP namespace segment. The ported JNI classes keep
 * their original `com.winlator.cmod` package (RFC §7) so the C exports resolve
 * with zero edits.
 */
object NativeLibraries {
    const val WINLATOR = "winlator"
    const val FAKEINPUT = "fakeinput"
}
