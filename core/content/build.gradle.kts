plugins {
    id("amphora.android.library")
}

android {
    namespace = "app.amphora.core.content"

    // The manifest URL lives in gradle.properties so the build (stageBundledContent)
    // and the runtime (ContentManifestLoader) cannot drift apart.
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField(
            "String",
            "CONTENT_MANIFEST_URL",
            "\"${providers.gradleProperty(app.amphora.buildlogic.CONTENT_MANIFEST_URL_PROPERTY).get()}\"",
        )
    }
}

dependencies {
    api(project(":core:common"))

    // org.json is part of the Android framework at runtime, but the JVM unit
    // test (ContentManifestTest) needs the standalone jar to parse JSON.
    testImplementation(libs.org.json)
}
