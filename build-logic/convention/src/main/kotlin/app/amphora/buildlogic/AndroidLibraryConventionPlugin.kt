package app.amphora.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // AGP 9 built-in Kotlin: no org.jetbrains.kotlin.android plugin.
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                configureCommonAndroid()
                defaultConfig {
                    minSdk = SDK_MIN
                }
            }
            addCommonTestDependencies()
        }
    }
}
