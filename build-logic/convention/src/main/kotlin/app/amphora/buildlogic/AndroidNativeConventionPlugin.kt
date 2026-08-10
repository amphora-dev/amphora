package app.amphora.buildlogic

import com.android.build.api.dsl.LibraryExtension
import java.io.File
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
                        targets("winlator", "amphora-exec")
                        val nativeCache =
                            rootProject.layout.projectDirectory
                                .dir(".native-cache")
                                .asFile
                        val fetchContentDir = nativeCache.resolve("fetchcontent").apply { mkdirs() }
                        arguments +=
                            listOf(
                                "-DANDROID_STL=c++_shared",
                                "-DANDROID_PLATFORM=android-26",
                                // Keep FetchContent (zstd/xz) outside per-variant .cxx so CI/local
                                // can cache the cloned sources across clean builds.
                                "-DFETCHCONTENT_BASE_DIR=${fetchContentDir.absolutePath}",
                            )
                        findCcache()?.let { ccache ->
                            arguments +=
                                listOf(
                                    "-DCMAKE_C_COMPILER_LAUNCHER=$ccache",
                                    "-DCMAKE_CXX_COMPILER_LAUNCHER=$ccache",
                                )
                        }
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

private fun findCcache(): String? {
    System.getenv("CCACHE_PROGRAM")?.takeIf { it.isNotBlank() }?.let { return it }
    val path = System.getenv("PATH") ?: return null
    for (dir in path.split(File.pathSeparatorChar)) {
        val candidate = File(dir, "ccache")
        if (candidate.isFile && candidate.canExecute()) {
            return candidate.absolutePath
        }
    }
    return null
}
