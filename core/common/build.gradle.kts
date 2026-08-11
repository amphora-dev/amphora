plugins {
    id("amphora.android.library")
}

android {
    namespace = "app.amphora.core.common"
    testFixtures {
        enable = true
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    testFixturesImplementation(libs.junit4)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
}
