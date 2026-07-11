plugins {
    id("amphora.android.library")
}

android {
    namespace = "app.amphora.core.content"
}

dependencies {
    api(project(":core:common"))
}
