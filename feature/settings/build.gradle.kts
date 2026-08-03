plugins {
    id("amphora.android.feature")
}

android {
    namespace = "app.amphora.feature.settings"
}

dependencies {
    implementation(libs.androidx.navigation.compose)
}
