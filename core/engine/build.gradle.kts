plugins {
    id("amphora.android.library")
    id("amphora.android.hilt")
}

android {
    namespace = "app.amphora.core.engine"
    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:content"))
    api(project(":core:container"))
    implementation(project(":core:native"))
    implementation(project(":core:rootfs"))

    // Ported com.winlator.cmod runtime kernel (RFC §7 - Java reused as-is).
    // Versions matched to WinNative's catalog for source compatibility.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.android.material)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(variantOf(libs.zstd.jni) { artifactType("aar") })
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.org.json)
}
