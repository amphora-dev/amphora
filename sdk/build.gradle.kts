plugins {
    id("amphora.android.library")
    id("amphora.android.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.amphora.sdk"
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
}
