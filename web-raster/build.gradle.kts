import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin (JVM): a rendered web page (pixels plus what the DOM says about text and pictures)
// becomes the gray raster of the glasses, always readable. No Android: testable on a PC, used by
// the browser of the watch app (M7) and the GeckoView probe (M2). src/main/js/page-layout.js is the
// script both run in the page to report its layout; their builds copy it into their extensions.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // LayoutParser reads the layout report of page-layout.js as JSON.
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Where RasterSnapshotTest writes its before/after pictures; it is skipped without.
    providers.gradleProperty("snapshotDir").orNull?.let { systemProperty("snapshotDir", it) }
    systemProperty("java.awt.headless", "true")
    testLogging { events("failed", "skipped") }
}
