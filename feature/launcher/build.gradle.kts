plugins {
    id("amphora.android.feature")
}

android {
    namespace = "app.amphora.feature.launcher"
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:rootfs"))
    implementation(project(":core:content"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
