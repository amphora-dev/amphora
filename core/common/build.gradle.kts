plugins {
    id("amphora.android.library")
}

android {
    namespace = "app.amphora.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.android)
}
