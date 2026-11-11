package app.amphora.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureCommonAndroid()
                defaultConfig {
                    minSdk = SDK_MIN
                    targetSdk = SDK_TARGET
                    // The runtime is arm64-v8a only.
                    ndk { abiFilters += "arm64-v8a" }
                }
                // One committed debug keystore (Android's public default credentials):
                // the generated ~/.android/debug.keystore differs per machine, and an
                // APK signed elsewhere cannot be installed over another one.
                signingConfigs {
                    getByName("debug") {
                        storeFile = rootProject.layout.projectDirectory.file("app/debug.keystore").asFile
                        storePassword = "android"
                        keyAlias = "androiddebugkey"
                        keyPassword = "android"
                    }
                }
                buildTypes {
                    getByName("debug") {
                        signingConfig = signingConfigs.getByName("debug")
                    }
                    getByName("release") {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                        // No release key yet: release builds are for measuring, signed like debug.
                        signingConfig = signingConfigs.getByName("debug")
                    }
                }
            }
            addCommonDependencies()
        }
    }
}
