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
        // NewPipeExtractor (search and stream addresses for the YouTube app) is only published on JitPack.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.teamnewpipe")
                includeGroup("com.github.TeamNewPipe")
            }
        }
        // GeckoView (MPL-2.0) for the browser and the M2 probe; nothing else is taken from Mozilla's repository.
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
include(":app-api")
include(":phone-link")
// The project holds exactly one app to run, so Android Studio cannot install the wrong one: G2 Watch, or with
// -Pg2.geckoTest=true (or that line in gradle.properties) the M2 GeckoView measuring app instead, or with
// -Pg2.phone=true G2 Handy for the phone. G2 Handy has the watch app's id: on the watch it would replace it.
when {
    providers.gradleProperty("g2.geckoTest").orNull == "true" -> {
        include(":gecko-probe")
        project(":gecko-probe").projectDir = file("tools/gecko-probe")
    }
    providers.gradleProperty("g2.phone").orNull == "true" -> include(":phone")
    else -> include(":app")
}

// App packages (docs/app-entwicklung/09): every folder in packages/ with a build file is one app.
file("packages").listFiles()
    ?.filter { File(it, "build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include(":packages:${it.name}") }
