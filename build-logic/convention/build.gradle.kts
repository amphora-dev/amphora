plugins {
    `kotlin-dsl`
}

group = "app.amphora.buildlogic"

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.serialization.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "amphora.android.application"
            implementationClass = "app.amphora.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "amphora.android.library"
            implementationClass = "app.amphora.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "amphora.android.compose"
            implementationClass = "app.amphora.buildlogic.AndroidComposeConventionPlugin"
        }
    }
}
