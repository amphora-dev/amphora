package app.amphora.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            val catalog = libs
            dependencies.apply {
                add("implementation", catalog.findLibrary("hilt-android").get())
                add("ksp", catalog.findLibrary("hilt-compiler").get())
                add("testImplementation", catalog.findLibrary("hilt-android-testing").get())
                add("kspTest", catalog.findLibrary("hilt-compiler").get())
                add("androidTestImplementation", catalog.findLibrary("hilt-android-testing").get())
                add("kspAndroidTest", catalog.findLibrary("hilt-compiler").get())
            }
        }
    }
}
