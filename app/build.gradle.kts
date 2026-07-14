import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("amphora.android.application")
    id("amphora.android.compose")
    id("amphora.android.hilt")
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
// Bundled-content asset staging
// ============================================================================
// Stages the assets referenced by core/content/src/main/assets/content_manifest.json
// into app/src/main/assets/ so they ship inside the APK and BundledContentSource can
// resolve them on-device with no remote download (bypasses the D4 nativeDownloadFile
// stub). ARCHIVE (.tzst) are copied from the local WinNative checkout; WCP (.wcp) are
// downloaded from the nicholasx417/WinNative-Components GitHub releases -- the same
// source the device's ContentsManager.syncContents() reads (REMOTE_PROFILES).
//
// Best-effort: a missing WinNative checkout, a failed download, or a SHA mismatch
// logs a warning and skips that asset; it never fails the build. The
// BundledContentSourceTest tiers are assumeTrue-gated on asset presence, so the build
// and tests pass without staging. NOT auto-wired: staging the 160 MB Proton .wcp into
// src/main/assets would bloat every debug APK, so run this explicitly before device
// content testing: `./gradlew :app:stageBundledContent` then `connectedDebugAndroidTest`.
// Staged assets are git-ignored (*.tzst / *.wcp). Delete them for a slim APK again.
// See docs/04-ASSET-MANIFEST.md §4 and docs/03-TRACKING.md §P2 #8.

val winnativeAssetsDir: File =
    File(
        providers.gradleProperty("amphora.winnative.dir")
            .orElse(rootProject.projectDir.parentFile.resolve("WinNative").absolutePath)
            .get(),
    ).resolve("app/src/main/assets")

val contentManifestFile: File =
    rootProject.file("core/content/src/main/assets/content_manifest.json")

// WCP download URLs (build-only; not part of the runtime manifest). Keyed by assetPath.
// Resolved from nicholasx417/WinNative-Components contents.json (REMOTE_PROFILES).
val wcpDownloadUrls = mapOf(
    "Proton-10.0-4-x86_64.wcp" to
        "https://github.com/nicholasx417/WinNative-Components/releases/download/Proton/Proton-10.0-4-x86_64.wcp",
    "Bionic-Box64-0.4.3-8ee3d8f2c.wcp" to
        "https://github.com/nicholasx417/WinNative-Components/releases/download/bionic-box64-nightly-0.4.3-8ee3d8f2c/Bionic-Box64-0.4.3-8ee3d8f2c.wcp",
)

val stagedAssetsDir: File = file("src/main/assets")
val wcpCacheDir = layout.buildDirectory.dir("content-cache")

private fun File.sha256(): String {
    val md = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val stageBundledContent by tasks.registering {
    group = "amphora content"
    description =
        "Stage bundled-content assets (.tzst from WinNative, .wcp from GitHub) into app/src/main/assets/. Best-effort."

    inputs.property("winnativeAssetsDir", winnativeAssetsDir.absolutePath)
    inputs.property("wcpDownloadUrls", wcpDownloadUrls.toString())
    inputs.file(contentManifestFile)

    // Up-to-date when every manifest asset is staged (ARCHIVE SHAs verified).
    outputs.upToDateWhen {
        try {
            val components =
                (JsonSlurper().parse(contentManifestFile) as Map<*, *>)["components"] as Map<*, *>
            components.values.all { entry ->
                val e = entry as Map<*, *>
                val staged = File(stagedAssetsDir, e["assetPath"] as String)
                staged.exists() && when (e["kind"] as String) {
                    "ARCHIVE" -> (e["sha256"] as String?)?.let { staged.sha256() == it } ?: true
                    else -> true
                }
            }
        } catch (t: Throwable) {
            logger.debug("[stageBundledContent] up-to-date check failed: $t", t)
            false
        }
    }

    doLast {
        val components =
            (JsonSlurper().parse(contentManifestFile) as Map<*, *>)["components"] as Map<*, *>
        val cacheDir = wcpCacheDir.get().asFile
        for ((id, entry) in components) {
            val e = entry as Map<*, *>
            val assetPath = e["assetPath"] as String
            val kind = e["kind"] as String
            val expectedSha = e["sha256"] as String?
            val staged = File(stagedAssetsDir, assetPath)
            staged.parentFile.mkdirs()
            when (kind) {
                "ARCHIVE" -> {
                    val src = File(winnativeAssetsDir, assetPath)
                    if (!src.exists()) {
                        logger.warn("[stageBundledContent] $id: source missing ($src); WinNative checkout absent? skipping.")
                        continue
                    }
                    if (staged.exists() && expectedSha != null && staged.sha256() == expectedSha) {
                        logger.lifecycle("[stageBundledContent] $id: already staged ($assetPath); skipping.")
                        continue
                    }
                    src.copyTo(staged, overwrite = true)
                    val actualSha = staged.sha256()
                    if (expectedSha != null && actualSha != expectedSha) {
                        logger.error("[stageBundledContent] $id: SHA-256 MISMATCH for $assetPath (expected $expectedSha, got $actualSha). Runtime resolve will reject this asset.")
                    } else {
                        logger.lifecycle("[stageBundledContent] $id: staged $assetPath (sha256=$actualSha).")
                    }
                }
                "WCP" -> {
                    if (staged.exists()) {
                        logger.lifecycle("[stageBundledContent] $id: already staged ($assetPath, sha256=${staged.sha256()}); skipping.")
                        continue
                    }
                    val url = wcpDownloadUrls[assetPath]
                    if (url == null) {
                        logger.warn("[stageBundledContent] $id: no download URL mapped for $assetPath; skipping. Add it to wcpDownloadUrls in app/build.gradle.kts.")
                        continue
                    }
                    cacheDir.mkdirs()
                    val cached = File(cacheDir, assetPath)
                    if (!cached.exists()) {
                        logger.lifecycle("[stageBundledContent] $id: downloading $url ...")
                        try {
                            val conn = URI(url).toURL().openConnection() as HttpURLConnection
                            conn.instanceFollowRedirects = true
                            conn.connectTimeout = 15_000
                            conn.readTimeout = 300_000
                            if (conn.responseCode !in 200..299) {
                                throw IOException("HTTP ${conn.responseCode} ${conn.responseMessage}")
                            }
                            conn.inputStream.use { input ->
                                cached.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                            }
                        } catch (t: Throwable) {
                            logger.warn("[stageBundledContent] $id: download failed ($t); skipping. Stage $assetPath manually under app/src/main/assets/ if needed.")
                            cached.delete()
                            continue
                        }
                    }
                    cached.copyTo(staged, overwrite = false)
                    logger.lifecycle("[stageBundledContent] $id: staged $assetPath (sha256=${staged.sha256()}). Paste into content_manifest.json to lock (currently null).")
                }
                else -> logger.warn("[stageBundledContent] $id: unknown kind '$kind'; skipping.")
            }
        }
    }
}
