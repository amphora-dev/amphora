plugins {
    id("amphora.android.library")
}

android {
    namespace = "app.amphora.core.rootfs"
}

dependencies {
    api(project(":core:common"))
}
