package app.amphora.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.findByType<ApplicationExtension>()?.apply {
                buildFeatures { compose = true }
            }
            extensions.findByType<LibraryExtension>()?.apply {
                buildFeatures { compose = true }
            }

            val catalog = libs
            dependencies.apply {
                add("implementation", platform(catalog.findLibrary("androidx-compose-bom").get()))
                add("implementation", catalog.findLibrary("androidx-compose-ui").get())
                add("implementation", catalog.findLibrary("androidx-compose-ui-graphics").get())
                add("implementation", catalog.findLibrary("androidx-compose-ui-tooling-preview").get())
                add("implementation", catalog.findLibrary("androidx-compose-material3").get())
                add("debugImplementation", catalog.findLibrary("androidx-compose-ui-tooling").get())
            }
        }
    }
}
