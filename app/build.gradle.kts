import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ch.madtreasures.g2watch"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.madtreasures.g2watch"
        // Wear OS 4 (API 33) and newer: Faceclaw's GATT code uses the API 33 write call.
        minSdk = 33
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(project(":faceclaw-android"))
    implementation(project(":firmware-image"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.wear.input)
    implementation(libs.newpipe.extractor) {
        // The javax.script binding of Rhino; NewPipeExtractor calls Rhino directly, and Android has no javax.script.
        exclude(group = "org.mozilla", module = "rhino-engine")
    }

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Robolectric's Android runtime needs this on JDK 17 and newer.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    // Where RenderSnapshotTest writes its pictures of the glasses display; it is skipped without.
    providers.gradleProperty("snapshotDir").orNull?.let { systemProperty("snapshotDir", it) }
    // RealImageTransferTest streams Even's real image when it is given here (never part of the repo).
    // Declared as an input, so setting it reruns the tests instead of reporting "UP-TO-DATE".
    val stockImage = System.getenv("G2_STOCK_IMAGE")
    inputs.property("g2StockImage", stockImage ?: "")
    if (stockImage != null && file(stockImage).isFile) {
        inputs.file(stockImage).withPropertyName("g2StockImageFile").withPathSensitivity(PathSensitivity.NONE)
        environment("G2_STOCK_IMAGE", stockImage)
    }
}
