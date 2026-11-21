plugins {
    id("amphora.android.application")
    id("amphora.android.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.amphora"

    defaultConfig {
        applicationId = "app.amphora"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    implementation(project(":sdk"))
    implementation(project(":ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
}
