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
            }
            addCommonTestDependencies()
        }
    }
}
