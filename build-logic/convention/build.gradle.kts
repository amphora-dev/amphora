plugins {
    `kotlin-dsl`
}

group = "app.amphora.buildlogic"

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit4)
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
        register("androidHilt") {
            id = "amphora.android.hilt"
            implementationClass = "app.amphora.buildlogic.AndroidHiltConventionPlugin"
        }
        register("androidNative") {
            id = "amphora.android.native"
            implementationClass = "app.amphora.buildlogic.AndroidNativeConventionPlugin"
        }
        register("androidFeature") {
            id = "amphora.android.feature"
            implementationClass = "app.amphora.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("contentStaging") {
            id = "amphora.content.staging"
            implementationClass = "app.amphora.buildlogic.ContentStagingConventionPlugin"
        }
    }
}
