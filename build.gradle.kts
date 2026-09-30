import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// --- App packages (docs/app-entwicklung/09) --------------------------------------------------------
// Every module in packages/ is one watch app. Its task `g2app` builds build/g2app/<id>-<version>.g2app:
// g2app.json (from the app's own manifest), classes.dex (D8) and src/main/assets.
// The watch app already contains Kotlin, kotlinx.serialization and the app interface (app-api), so the
// DEX leaves them out; any other library the package uses goes into it.

/** Groups of the libraries the watch app provides to every package. */
val hostProvidedGroups = setOf("org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx")

/** D8, from the version catalog of the root project (subprojects see their own extensions). */
val r8 = libs.r8

/** Same minimum Android version as the watch app. */
val packageMinApi = 33

/** The Android SDK that Android Studio (local.properties) or the environment names. */
fun androidSdk(): File {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { props.load(it) }
    val path = props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException("Android SDK nicht gefunden: sdk.dir in local.properties oder ANDROID_HOME setzen")
    return File(path)
}

/** [name] in the newest subfolder of [dir] that has it, e.g. platforms/android-<n>/android.jar. */
fun newestIn(dir: File, name: String): File {
    fun version(f: File) = Regex("\\d+").findAll(f.parentFile.name).map { it.value.toInt() }.toList()
    val versions = Comparator<List<Int>> { a, b ->
        a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
    }
    return dir.listFiles().orEmpty().map { File(it, name) }.filter { it.isFile }.maxWithOrNull(compareBy(versions) { version(it) })
        ?: throw GradleException("$name fehlt in $dir – im SDK Manager von Android Studio installieren")
}

subprojects {
    if (parent?.path != ":packages") return@subprojects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    val d8 = configurations.create("g2appD8")
    dependencies {
        "compileOnly"(project(":app-api"))
        "g2appD8"(r8)
    }

    val main = extensions.getByType<SourceSetContainer>()["main"]
    val assets = file("src/main/assets")
    val work = layout.buildDirectory.dir("g2app-work")
    val runtime = configurations["runtimeClasspath"]
    val compile = configurations["compileClasspath"]
    val bundled = runtime.incoming.artifactView {
        componentFilter { id ->
            when (id) {
                is ModuleComponentIdentifier -> id.group !in hostProvidedGroups
                is ProjectComponentIdentifier -> id.projectPath != ":app-api"
                else -> true
            }
        }
    }.files

    val manifest = tasks.register<JavaExec>("g2appManifest") {
        description = "Writes g2app.json from the manifest of the package's G2App."
        val out = work.map { it.file("g2app.json") }
        classpath = files(main.output, compile, runtime)
        mainClass.set("ch.madtreasures.g2watch.apps.PackageManifestTool")
        inputs.files(fileTree(assets)).withPropertyName("assets")
        outputs.file(out)
        argumentProviders.add(CommandLineArgumentProvider {
            main.output.classesDirs.map { it.path } + listOf("--assets", assets.path, "--out", out.get().asFile.path)
        })
    }

    val jar = tasks.named<Jar>("jar")
    val dex = tasks.register<JavaExec>("g2appDex") {
        description = "Compiles the package's classes and bundled libraries to DEX with D8."
        val out = work.map { it.dir("dex") }
        inputs.files(jar).withPropertyName("jar")
        inputs.files(bundled).withPropertyName("bundled")
        outputs.dir(out)
        classpath = d8
        mainClass.set("com.android.tools.r8.D8")
        doFirst { out.get().asFile.run { deleteRecursively(); mkdirs() } }
        argumentProviders.add(CommandLineArgumentProvider {
            val program = listOf(jar.get().archiveFile.get().asFile) + bundled.files
            val library = compile.files - bundled.files
            listOf("--release", "--min-api", "$packageMinApi", "--output", out.get().asFile.path) +
                listOf("--lib", newestIn(File(androidSdk(), "platforms"), "android.jar").path) +
                library.flatMap { listOf("--classpath", it.path) } +
                program.map { it.path }
        })
    }

    tasks.register<Zip>("g2app") {
        group = "g2app"
        description = "Builds the app package build/g2app/<id>-<version>.g2app (docs/app-entwicklung/09)."
        from(manifest)
        from(dex)
        from(assets) { into("assets") }
        destinationDirectory.set(layout.buildDirectory.dir("g2app"))
        // Named after the app, which only g2app.json knows; before it exists (IDE sync) a placeholder.
        archiveFileName.set(manifest.map { task ->
            val json = task.outputs.files.singleFile.takeIf { it.isFile }?.readText().orEmpty()
            fun field(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val id = field("id") ?: return@map "${project.name}.g2app"
            "$id-${field("version")}.g2app"
        })
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        doFirst { destinationDirectory.get().asFile.listFiles { f -> f.name.endsWith(".g2app") }?.forEach { it.delete() } }
    }
}

// All packages side by side in build/g2app/, as the CI publishes them.
tasks.register<Sync>("g2appPackages") {
    group = "g2app"
    description = "Builds every app package in packages/ into build/g2app/."
    val packages = subprojects.filter { it.parent?.path == ":packages" }
    dependsOn(packages.map { "${it.path}:g2app" })
    from(packages.map { it.layout.buildDirectory.dir("g2app") }) { include("*.g2app") }
    into(layout.buildDirectory.dir("g2app"))
}
