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

// KSP2 workers share the Gradle daemon JVM (NoIsolation) and initialize IntelliJ
// PathManager. Pin absolute home/config paths so CI does not flake on missing
// product-info.json (google/ksp#3057 / KT-87900). gradle.properties sets the same
// via jvmargs for daemon startup; this refreshes abs paths every settings eval.
run {
    val root = rootDir
    val ideaHome = root.resolve(".ci/idea-home")
    val ideaConfig = root.resolve("build/idea-config")
    ideaConfig.mkdirs()
    System.setProperty("idea.home.path", ideaHome.absolutePath)
    System.setProperty("idea.config.path", ideaConfig.absolutePath)
}

include(":app")
include(":core:common")
include(":core:native")
include(":core:rootfs")
include(":core:content")
include(":core:container")
include(":core:engine")
include(":feature:launcher")
include(":feature:settings")
