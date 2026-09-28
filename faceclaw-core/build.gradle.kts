import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Faceclaw's shared Kotlin core (native/kotlin/shared), vendored unchanged. See UPSTREAM.md.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "com.faceclaw.shared"
        compileSdk = 37
        minSdk = 33
        withJava()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTestBuilder {}.configure {}
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
        }
    }
}
