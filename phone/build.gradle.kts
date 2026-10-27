import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// G2 Handy (docs/app-entwicklung/05 §6, 06 §1), the phone half of G2 Watch. It needs the watch app's
// applicationId and key, or Wear OS delivers nothing between the two. So far it passes the connections of
// watch apps through to partner apps on the phone (packages/fluency/PLATTFORM.md §6). The project holds one
// app to run at a time (settings.gradle.kts): this module only with -Pg2.phone=true.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ch.madtreasures.g2handy"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.madtreasures.g2watch"
        minSdk = 31
        targetSdk = 37
        // Raised when G2 Handy itself changes; it works with the watch app from this version on (0.10.0).
        versionCode = 13
        versionName = "0.10.0"
    }

    // The key both apps share (signing/README.md).
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/g2-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":phone-link"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach {
    // Robolectric's Android runtime needs this on JDK 17 and newer.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
}
