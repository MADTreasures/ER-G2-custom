import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin (JVM) firmware image pipeline: EVENOTA validation, g2flash patch set, allow-list.
// No Android and no Bluetooth: everything here is testable on a PC.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // RealImageTest reads Even's stock image from here when set (never part of the repo).
    System.getenv("G2_STOCK_IMAGE")?.let { environment("G2_STOCK_IMAGE", it) }
    testLogging { events("failed", "skipped"); showStandardStreams = true }
}
