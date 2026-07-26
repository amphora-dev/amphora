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
        testInstrumentationRunner = "app.amphora.HiltTestRunner"
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
    implementation(project(":core:common"))
    implementation(project(":core:engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    androidTestImplementation(project(":core:engine"))
    androidTestImplementation(project(":core:rootfs"))
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
    wcpCatalogUrl.set(
        "https://raw.githubusercontent.com/nicholasx417/WinNative-Components/main/default.json"
    )
    wcpDownloadUrls.set(
        mapOf(
            "Proton-10.0-4-x86_64.wcp" to
                "https://github.com/nicholasx417/WinNative-Components/releases/download/Proton/Proton-10.0-4-x86_64.wcp",
            "Dxvk-3.0.2-gplasync.wcp" to
                "https://github.com/nicholasx417/WinNative-Components/releases/download/Stable-Dxvk/Dxvk-3.0.2-gplasync.wcp",
        )
    )
}

// ============================================================================
// Instrumented-test orchestration
// ============================================================================
// `stageBundledContent` is deliberately NOT wired to `preBuild` (the 160 MB
// Proton .wcp would bloat every debug APK). But the androidTest suite needs
// those assets staged, and running `connectedDebugAndroidTest` without staging
// silently `assumeTrue`-skips every asset-gated test (GameSessionLaunchTest,
// ImagefsExtractionTest, PreparerGraphicsDriverTest, BundledContentSourceTest).
// This aggregate task stages content first so the full suite actually runs:
//
//   ./gradlew :app:connectedAndroidTestWithContent
//
// Use plain `:app:connectedDebugAndroidTest` for quick runs without assets.
tasks.register("connectedAndroidTestWithContent") {
    group = "amphora content"
    description =
        "Run connectedDebugAndroidTest after staging bundled content (imagefs/.wcp/.tzst) " +
            "into the app APK, so asset-gated tests run instead of assumeTrue-skipping."
    dependsOn("stageBundledContent")
    dependsOn("connectedDebugAndroidTest")
}
