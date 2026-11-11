package app.amphora.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // AGP 9 built-in Kotlin: no org.jetbrains.kotlin.android plugin.
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                configureCommonAndroid()
                defaultConfig {
                    minSdk = SDK_MIN
                    consumerProguardFiles("consumer-rules.pro")
                }
            }
            // Libraries state what they expose: every public declaration needs an
            // explicit visibility and type. Tests are exempt.
            tasks.withType<KotlinCompilationTask<*>>().configureEach {
                if (!name.contains("Test")) {
                    compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
                }
            }
            addCommonDependencies()
        }
    }
}
