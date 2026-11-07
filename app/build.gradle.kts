// M1 host: Wine's own WineActivity (the JNI entry points in wineandroid.so are bound
// to org.winehq.wine.WineActivity). Replaced by the SDK host in milestone 4.
plugins {
    id("com.android.application")
}

android {
    namespace = "org.winehq.wine"
    compileSdk = 36

    defaultConfig {
        // Not "app.amphora": that id belongs to the old app still installed on test devices.
        applicationId = "app.amphora.next"
        minSdk = 28
        // Wine execs wineserver and its loader from the app's files dir; targetSdk 29+
        // forbids executing app data files (W^X).
        @Suppress("ExpiredTargetSdkVersion")
        targetSdk = 28
        versionCode = 1
        versionName = "0.0.1-m1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}
