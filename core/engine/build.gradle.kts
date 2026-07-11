plugins {
    id("amphora.android.library")
    id("amphora.android.hilt")
}

android {
    namespace = "app.amphora.core.engine"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:content"))
    api(project(":core:container"))
    implementation(project(":core:native"))
    implementation(project(":core:rootfs"))
}
