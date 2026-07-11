package app.amphora.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidNativeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // Requires amphora.android.library to be applied first (sets compileSdk/minSdk/common).
        extensions.configure<LibraryExtension> {
            ndkVersion = NDK_VERSION
            defaultConfig {
                ndk { abiFilters += "arm64-v8a" }
                externalNativeBuild {
                    cmake {
                        arguments += listOf(
                            "-DANDROID_STL=c++_shared",
                            "-DANDROID_PLATFORM=android-26",
                        )
                        cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
                    }
                }
            }
            externalNativeBuild {
                cmake {
                    path = file("src/main/cpp/CMakeLists.txt")
                    version = CMAKE_VERSION
                }
            }
        }
    }
}
