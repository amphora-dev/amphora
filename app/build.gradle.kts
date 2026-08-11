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
        // Main CI overrides these (-Pamphora.versionCode / -Pamphora.versionName)
        // when publishing the rolling `apk` Release. Local/PR defaults stay at 1 / 0.1.0.
        versionCode =
            providers
                .gradleProperty("amphora.versionCode")
                .map { it.toInt() }
                .orElse(1)
                .get()
        versionName =
            providers
                .gradleProperty("amphora.versionName")
                .orElse("0.1.0")
                .get()
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
    // Manifest registers ShizukuProvider directly; keep it on :app's lint
    // compile classpath instead of relying on :core:engine's implementation edge.
    implementation(libs.shizuku.provider)

    androidTestImplementation(project(":core:engine"))
    androidTestImplementation(project(":core:rootfs"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ============================================================================
// Bundled-content asset staging (amphora.content.staging convention plugin)
// ============================================================================
// What to stage comes from the remote content_manifest.json -- the same URL the app
// fetches at runtime (`amphora.contentManifest.url`), including each WCP's remoteUrl
// and its catalog fallback. A pin bump upstream therefore needs no edit here. Pass
// -Pamphora.contentManifest.file=<path> to stage from a local manifest instead.
//
// The only build-machine-specific input is the WinNative checkout that holds the
// kernel-direct .tzst assets.
//
// Run explicitly: `./gradlew :app:stageBundledContent` (NOT auto-wired -- the 160 MB
// Proton .wcp would bloat every debug APK). The plugin exactly synchronizes verified
// manifest assets under build/generated/assets/bundledContent and registers that
// directory with the main Android asset source set. `clean` restores a slim APK.
// See docs/04-ASSET-MANIFEST.md §4.
amphoraContentStaging {
    winnativeDir.set(
        file(
            providers
                .gradleProperty("amphora.winnative.dir")
                .orElse(
                    rootProject.projectDir.parentFile
                        .resolve("WinNative")
                        .absolutePath,
                ).get(),
        ).resolve("app/src/main/assets"),
    )
}

// ============================================================================
// Instrumented-test orchestration
// ============================================================================
// `stageBundledContent` is deliberately NOT wired to `preBuild` (the 160 MB
// Proton .wcp would bloat every debug APK). But the androidTest suite needs
// those assets staged, and running `connectedDebugAndroidTest` without staging
// silently `assumeTrue`-skips every asset-gated test (GameSessionLaunchTest,
// ImagefsExtractionTest, PreparerGraphicsDriverTest, RemoteContentSourceTest).
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
