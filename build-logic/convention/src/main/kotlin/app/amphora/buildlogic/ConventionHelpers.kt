package app.amphora.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal const val SDK_COMPILE = 37
internal const val SDK_TARGET = 36
internal const val SDK_MIN = 26
internal const val NDK_VERSION = "28.0.13004108"
internal const val CMAKE_VERSION = "3.31.1"

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
