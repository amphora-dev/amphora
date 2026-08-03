plugins {
    id("amphora.android.library")
    id("amphora.android.native")
}

android {
    namespace = "app.amphora.core.nativelib"
}

dependencies {
    // `:core:native` is pure C/C++ + CMake: it builds libwinlator.so
    // (see src/main/cpp/CMakeLists.txt). The com.winlator.cmod JNI *binding*
    // classes live in :core:engine (native never depends upward).
}
