pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView (MPL-2.0) for the M2 probe; nothing else is taken from Mozilla's repository.
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "G2Watch"
include(":faceclaw-core")
include(":faceclaw-android")
include(":firmware-image")
include(":web-raster")
include(":app")
// M2: GeckoView feasibility probe, a separate APK so the watch app does not grow by ~90 MB.
include(":gecko-probe")
project(":gecko-probe").projectDir = file("tools/gecko-probe")
