package app.amphora.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("amphora.android.library")
        pluginManager.apply("amphora.android.compose")
        pluginManager.apply("amphora.android.hilt")
    }
}
