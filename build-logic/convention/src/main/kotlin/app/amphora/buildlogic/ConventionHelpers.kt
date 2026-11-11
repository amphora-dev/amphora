package app.amphora.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal const val SDK_COMPILE = 37

// Wine runs from the app's files dir. Apps targeting API 29+ may not execve()
// files there (W^X); the SDK starts the loader and wineserver through
// /system/bin/linker64, and Wine does the same for its own children.
internal const val SDK_TARGET = 36

// The runtime is built for API 30 (amphora-engine's target triple; the runtime
// manifest says so too).
internal const val SDK_MIN = 30

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Shared Android settings. With AGP 9's built-in Kotlin, the Kotlin JVM target
 * follows compileOptions.targetCompatibility.
 */
internal fun CommonExtension.configureCommonAndroid() {
    compileSdk = SDK_COMPILE
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17
    testOptions.unitTests.isReturnDefaultValues = true
    lint.apply {
        // A warning nobody has to act on is a warning everybody learns to skip.
        warningsAsErrors = true
        abortOnError = true
        ignoreTestSources = true
        // "A newer version exists" is upstream news, not a defect in this tree.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

internal fun Project.addCommonDependencies() {
    val catalog = libs
    dependencies.apply {
        add("implementation", catalog.findLibrary("kotlinx-coroutines-android").get())
        add("testImplementation", catalog.findLibrary("junit4").get())
        add("testImplementation", catalog.findLibrary("kotlinx-coroutines-test").get())
    }
}
