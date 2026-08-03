package app.amphora.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // AGP 9 built-in Kotlin: no org.jetbrains.kotlin.android plugin.
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureCommonAndroid()
                defaultConfig {
                    minSdk = SDK_MIN
                    targetSdk = SDK_TARGET
                    // Package only arm64-v8a. Our own CMake (see AndroidNativeConventionPlugin)
                    // already builds arm64-only; this also filters prebuilt .so files shipped by
                    // AAR dependencies (e.g. zstd-jni, androidx.graphics:graphics-path) so that
                    // armeabi-v7a / x86 / x86_64 variants are not merged into the APK.
                    ndk { abiFilters += "arm64-v8a" }
                }
                // adrenotools (driver.h) REQUIRES useLegacyPackaging = true: it installs
                // GPU driver hooks into nativeLibraryDir and dlopen()s them from that path,
                // which only works when .so files are extracted to disk. Also matches
                // upstream WinNative / Bannerlator. Safe for 16KB page-size devices - all
                // .so LOAD segments are 0x4000-aligned (NDK r28 default), so mmap from
                // disk works on 16KB kernels.
                packaging {
                    jniLibs {
                        useLegacyPackaging = true
                    }
                }
                // Shared debug signing keystore. Android's default debug.keystore is
                // machine-local (~/.android/debug.keystore, auto-generated on first build),
                // so debug APKs built on different machines have mismatched signatures and
                // can't be re-installed over each other (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
                // Committing one shared keystore pins the debug signature across all machines
                // and CI. Credentials are the Android-fixed defaults (public by convention).
                signingConfigs {
                    getByName("debug") {
                        storeFile =
                            rootProject.layout.projectDirectory
                                .dir("app")
                                .file("debug.keystore")
                                .asFile
                        storePassword = "android"
                        keyAlias = "androiddebugkey"
                        keyPassword = "android"
                    }
                }
                buildTypes {
                    getByName("debug") {
                        signingConfig = signingConfigs.getByName("debug")
                    }
                }
            }
            addCommonTestDependencies()
        }
    }
}
