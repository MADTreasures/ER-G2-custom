import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// M2 (07_Umsetzungsplan): a small separate APK that measures GeckoView on the real watch. Built only
// for armeabi-v7a: the Pixel Watch 5 runs 32-bit apps (Android Studio: "Device supports armeabi-v7a,
// armeabi"), and GeckoView ships one artifact per ABI (~90 MB each). With a single ABI and release as
// the default build type, Android Studio installs the measuring build without choosing a variant.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ch.madtreasures.g2watch.geckoprobe"
    compileSdk = 37
    // GeckoView 157 is built against API 37.1 and requires it to compile (checkAarMetadata).
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "ch.madtreasures.g2watch.geckoprobe"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GECKOVIEW_VERSION", "\"${libs.versions.geckoview.get()}\"")
        buildConfigField("String", "APK_ABI", "\"armeabi-v7a\"")
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildTypes {
        // Measure with the release variant: a debuggable app runs slower and needs more memory.
        // It is signed with the debug key so Android Studio can install it without a keystore.
        release {
            isDefault = true
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // libxul alone is over 100 MB; compressed the APK is about half as big, which matters when it
    // goes to the watch over Wi-Fi debugging. The libraries are unpacked once when installing.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric: the probe's screen and activity start without a watch.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // A watch-only test APK: ChromeOS devices are not a target.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The page preview (PagePreviewTest) renders what tools/page-preview/capture.js wrote into
// PREVIEW_DIR; with a new capture the tests must run again even though no source changed.
tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { System.getenv("PREVIEW_DIR").isNullOrBlank() }
}

dependencies {
    implementation(project(":web-raster"))
    implementation(libs.geckoview.armeabi.v7a)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
