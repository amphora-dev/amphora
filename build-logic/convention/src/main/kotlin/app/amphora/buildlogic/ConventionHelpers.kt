package app.amphora.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal const val SDK_COMPILE = 37

// App-private AArch64 ELF files are launched through /system/bin/linker64 instead
// of being passed directly to execve. libamphora-exec.so keeps that routing in
// place for Box64/Wine descendants, satisfying Android 10+'s W^X policy.
internal const val SDK_TARGET = 36
// Published Box64, Vulkan wrapper, and Proton wineserver artifacts require
// Bionic's LIBC_R symbol set (notably memfd_create), which starts at API 30.
internal const val SDK_MIN = 30
internal const val NDK_VERSION = "28.2.13676358"
internal const val CMAKE_VERSION = "3.31.5"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Common android config. With AGP 9 built-in Kotlin, jvmTarget defaults to
 * [compileOptions.targetCompatibility], so no explicit kotlin jvmTarget is set.
 */
internal fun CommonExtension.configureCommonAndroid() {
    compileSdk = SDK_COMPILE
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.isIncludeAndroidResources = true
    // JVM unit-test coverage reports (createDebugUnitTestCoverageReport).
    buildTypes.configureEach {
        if (name == "debug") {
            enableUnitTestCoverage = true
        }
    }
    lint.apply {
        // A warning nobody has to act on is a warning everybody learns to skip.
        warningsAsErrors = true
        abortOnError = true
        // Without this, lint analyses every module's unitTest and androidTest source
        // set as well: 27 analysis passes across 9 modules instead of 9, two thirds
        // of them over code that ships nowhere. checkTestSources is already false,
        // so nothing was being reported from them either way.
        ignoreTestSources = true
        // "A newer version exists" is upstream news, not a defect in this tree; it
        // would turn CI red on someone else's release. Dependency bumps are a
        // deliberate act, tracked in gradle/libs.versions.toml.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

internal fun Project.addCommonTestDependencies() {
    val catalog = libs
    dependencies.apply {
        add("testImplementation", catalog.findLibrary("junit4").get())
        add("testImplementation", catalog.findLibrary("kotlinx-coroutines-test").get())
        add("testImplementation", catalog.findLibrary("mockk").get())
        add("testImplementation", catalog.findLibrary("turbine").get())
        add("androidTestImplementation", catalog.findLibrary("androidx-test-junit").get())
        add("androidTestImplementation", catalog.findLibrary("androidx-test-core").get())
        add("androidTestImplementation", catalog.findLibrary("androidx-test-runner").get())
        add("androidTestImplementation", catalog.findLibrary("androidx-test-rules").get())
        add("androidTestImplementation", catalog.findLibrary("espresso-core").get())
        add("androidTestImplementation", catalog.findLibrary("mockk-android").get())
    }
}
