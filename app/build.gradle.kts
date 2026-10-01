import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** The app packages in packages/ (docs/app-entwicklung/09), as settings.gradle.kts includes them. */
val appPackages: List<File> = rootDir.resolve("packages").listFiles().orEmpty()
    .filter { File(it, "build.gradle.kts").isFile }
    .sortedBy { it.name }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ch.madtreasures.g2watch"
    compileSdk = 37
    // GeckoView 157 (the browser, 05 §5) is built against API 37.1 and requires it to compile.
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "ch.madtreasures.g2watch"
        // Wear OS 4 (API 33) and newer: Faceclaw's GATT code uses the API 33 write call.
        minSdk = 33
        targetSdk = 37
        versionCode = 10
        versionName = "0.8.0"
        // GeckoView ships one native build per ABI (~90 MB each in the APK): only the Pixel Watch 5's,
        // which runs 32-bit apps. For another watch: adb shell getprop ro.product.cpu.abilist, and
        // change this and the geckoview artifact below (05 §5).
        ndk { abiFilters += "armeabi-v7a" }
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

    // GeckoView's libraries are 136 MB; compressed they take 75 MB of the APK, which matters when it goes
    // to the watch over Wi-Fi debugging. Android unpacks them once when installing.
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
    }

    lint {
        // A watch app with GeckoView for the watch's ABI only: ChromeOS devices are not a target.
        disable += "ChromeOsAbiSupport"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    // The tests of the app packages (09 §4) run here, with the host and its fakes.
    sourceSets.getByName("test").kotlin.srcDirs(appPackages.map { File(it, "src/test/kotlin") })
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":app-api"))
    implementation(project(":faceclaw-android"))
    implementation(project(":firmware-image"))
    // The browser (05 §10, M7): GeckoView paints pages, web-raster turns them into the glasses' picture.
    // page-layout.js of web-raster goes into assets/webbridge/ (root build.gradle.kts).
    implementation(project(":web-raster"))
    implementation(libs.geckoview.armeabi.v7a)

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

    appPackages.forEach { testImplementation(project(":packages:${it.name}")) }
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Robolectric's Android runtime needs this on JDK 17 and newer.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    // BuiltPackagesTest installs the real package files of packages/ (09 §4).
    val packageDirs = appPackages.map { File(it, "build/g2app") }
    dependsOn(appPackages.map { ":packages:${it.name}:g2app" })
    inputs.files(packageDirs.map { fileTree(it) { include("*.g2app") } }).withPropertyName("appPackages")
    systemProperty("g2app.packages", packageDirs.joinToString(File.pathSeparator))
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
