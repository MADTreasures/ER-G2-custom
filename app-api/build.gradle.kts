import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The interface of watch apps (docs/app-entwicklung/03 §2): G2App, AppContext, events, commands, pages.
// Pure Kotlin, so app packages (packages/…, 09) compile against it without the watch app itself.
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
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
