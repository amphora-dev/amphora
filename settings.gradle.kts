pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "amphora"

// :sdk is the engine (runtime, prefixes, settings, sessions, the game window);
// :app is a thin shell on its public API; :ui is the design system both use.
include(":ui")
include(":sdk")
include(":app")
