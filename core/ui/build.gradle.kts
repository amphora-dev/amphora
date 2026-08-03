plugins {
    id("amphora.android.library")
    id("amphora.android.compose")
}

android {
    namespace = "app.amphora.core.ui"
}

dependencies {
    api(project(":core:common"))
}
