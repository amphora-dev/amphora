plugins {
    id("amphora.android.library")
}

android {
    namespace = "app.amphora.core.container"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:content"))
}
