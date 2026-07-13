plugins {
    id("amphora.android.application")
    id("amphora.android.compose")
    id("amphora.android.hilt")
}

android {
    namespace = "app.amphora"

    defaultConfig {
        applicationId = "app.amphora"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":feature:launcher"))
    implementation(project(":feature:settings"))
    implementation(project(":core:ui"))
    implementation(project(":core:common"))
    implementation(project(":core:engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    androidTestImplementation(project(":core:engine"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
