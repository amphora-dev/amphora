plugins {
    id("amphora.android.library")
    id("amphora.android.native")
}

android {
    namespace = "app.amphora.core.nativelib"
}

dependencies {
    // `:core:native` is pure C/C++ + CMake: it builds libwinlator.so +
    // libfakeinput.so (see src/main/cpp/CMakeLists.txt). The com.winlator.cmod
    // JNI *binding* classes are deferred to :core:engine (P1) -- they import the
    // runtime kernel, so architecture forbids them here (native never depends
    // upward). The .so resolves its JNI exports by symbol name regardless.
}
