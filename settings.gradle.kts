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
    }
}

rootProject.name = "G2Watch"
include(":faceclaw-core")
include(":faceclaw-android")
include(":firmware-image")
include(":app")
include(":app-api")

// App packages (docs/app-entwicklung/09): every folder in packages/ with a build file is one app.
file("packages").listFiles()
    ?.filter { File(it, "build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include(":packages:${it.name}") }
