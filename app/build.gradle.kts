plugins {
    id("amphora.android.application")
    id("amphora.android.compose")
    id("amphora.android.hilt")
    id("amphora.content.staging")
}

android {
    namespace = "app.amphora"

    defaultConfig {
        applicationId = "app.amphora"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":feature:launcher"))
    implementation(project(":feature:settings"))
    implementation(project(":core:ui"))
    implementation(project(":core:common"))
    implementation(project(":core:engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    androidTestImplementation(project(":core:engine"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ============================================================================
// Bundled-content asset staging (amphora.content.staging convention plugin)
// ============================================================================
// The manifest (content_manifest.json) is resolved automatically from :core:content
// -- no cross-module path here. Only the *external* asset sources are configured
// below: the WinNative checkout (ARCHIVE .tzst source) and the .wcp download URLs.
//
// Run explicitly: `./gradlew :app:stageBundledContent` (NOT auto-wired -- the 160 MB
// Proton .wcp would bloat every debug APK). Staged assets are git-ignored (*.tzst /
// *.wcp); delete them for a slim APK again. See docs/04-ASSET-MANIFEST.md §4,
// docs/03-TRACKING.md §P2 #9.
amphoraContentStaging {
    winnativeDir.set(
        file(
            providers.gradleProperty("amphora.winnative.dir")
                .orElse(rootProject.projectDir.parentFile.resolve("WinNative").absolutePath)
                .get()
        ).resolve("app/src/main/assets")
    )
    wcpDownloadUrls.set(
        mapOf(
            "Proton-10.0-4-x86_64.wcp" to
                "https://github.com/nicholasx417/WinNative-Components/releases/download/Proton/Proton-10.0-4-x86_64.wcp",
            "Bionic-Box64-0.4.3-8ee3d8f2c.wcp" to
                "https://github.com/nicholasx417/WinNative-Components/releases/download/bionic-box64-nightly-0.4.3-8ee3d8f2c/Bionic-Box64-0.4.3-8ee3d8f2c.wcp",
        )
    )
}
