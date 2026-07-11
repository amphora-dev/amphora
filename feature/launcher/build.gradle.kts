plugins {
    id("amphora.android.feature")
}

android {
    namespace = "app.amphora.feature.launcher"
}

dependencies {
    implementation(project(":core:engine"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
