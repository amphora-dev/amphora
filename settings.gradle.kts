pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Amphora"

include(":app")
include(":core:common")
include(":core:native")
include(":core:rootfs")
include(":core:content")
include(":core:container")
include(":core:engine")
include(":feature:launcher")
include(":feature:settings")
