import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Faceclaw's Android BLE glue (App_Resources/Android/.../com/faceclaw/app), vendored unchanged. See UPSTREAM.md.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.faceclaw.android"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":faceclaw-core"))
}
