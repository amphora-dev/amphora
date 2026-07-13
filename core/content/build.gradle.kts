plugins {
    id("amphora.android.library")
}

android {
    namespace = "app.amphora.core.content"
}

dependencies {
    api(project(":core:common"))

    // org.json is part of the Android framework at runtime, but the JVM unit
    // test (ContentManifestTest) needs the standalone jar to parse JSON.
    testImplementation("org.json:json:20240303")
}
