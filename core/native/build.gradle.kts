plugins {
    id("amphora.android.library")
    id("amphora.android.native")
}

android {
    namespace = "app.amphora.core.nativelib"
}

dependencies {
    // The com.winlator.cmod JNI binding classes (12) are ported here per RFC §7/D5.
    // C/C++ sources live in src/main/cpp (single CMakeLists.txt -> libwinlator.so +
    // libfakeinput.so). Until the port lands, stub sources prove the NDK pipeline.
}
