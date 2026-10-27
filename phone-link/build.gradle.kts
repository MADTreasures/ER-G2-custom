import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The wire between the watch app and G2 Handy for connections to partner apps on the phone (Wear OS Data
// Layer paths and payloads). Pure Kotlin: both apps use it, and it is testable on a PC.
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
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped"); showStandardStreams = true }
}
