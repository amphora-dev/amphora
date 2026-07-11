plugins {
    id("amphora.android.feature")
}

android {
    namespace = "app.amphora.feature.settings"
}

dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
