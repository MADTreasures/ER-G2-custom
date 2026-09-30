import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin (JVM): a rendered web page (pixels plus what the DOM says about text and pictures)
// becomes the gray raster of the glasses, always readable. No Android: testable on a PC, used by
// the GeckoView probe (M2) and later by the browser on the glasses (M7).
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
