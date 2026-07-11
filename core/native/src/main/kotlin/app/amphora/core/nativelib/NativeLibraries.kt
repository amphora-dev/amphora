package app.amphora.core.nativelib

/**
 * Native library names loaded via `System.loadLibrary` (RFC §7 / D5).
 *
 * The 12 `com.winlator.cmod` JNI binding classes call into `libwinlator.so`
 * (48 exports across 9 source files, grouped into 7 JNI groups). `libfakeinput.so`
 * is an LD_PRELOAD input shim with no JNI. `libadrenotools` is statically linked
 * into `libwinlator.so` (Turnip driver loading).
 *
 * The real C sources are ported wholesale from WinNative `cpp/winlator/` (single
 * CMakeLists.txt: FetchContent zstd v1.5.6 / xz v5.4.6, adrenotools submodule
 * add_subdirectory, 19 GLSL shaders via glslc + bin2c.cmake). Until that port
 * lands, `src/main/cpp/CMakeLists.txt` builds stubs so the NDK pipeline is
 * verified end-to-end.
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
