import java.util.Properties
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Allocate at execution time, so sync/help/clean do not consume a version.
// Keep the counter outside build/ so `clean` cannot reset it.
@org.gradle.work.DisableCachingByDefault(because = "Each build needs a fresh APK version")
abstract class AllocateApkVersion : DefaultTask() {
    @get:Internal abstract val counterFile: RegularFileProperty
    @get:Internal abstract val lockFile: RegularFileProperty
    @get:OutputFile abstract val versionFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun allocate() {
        val lock = lockFile.get().asFile
        lock.parentFile.mkdirs()
        RandomAccessFile(lock, "rw").use { handle ->
            handle.channel.lock().use {
                val counter = counterFile.get().asFile
                val version = Properties().apply { counter.inputStream().use { load(it) } }
                val previousCode = version.getProperty("versionCode").toInt()
                val parts = version.getProperty("versionName").split('.').toMutableList()
                require(parts.size == 3 && parts.all { it.toIntOrNull()?.let { n -> n >= 0 } == true }) {
                    "versionName must be non-negative major.minor.patch"
                }
                // Confirmed history: 1.6.0 preceded automatic allocation; the
                // first verified automatic release is 1.6.1 / code 18.
                val minimumVersion = listOf(1, 6, 1)
                val comparison = parts.map { it.toInt() }.zip(minimumVersion)
                    .map { (actual, minimum) -> actual.compareTo(minimum) }
                    .firstOrNull { it != 0 } ?: 0
                require(previousCode >= 18 && comparison >= 0) {
                    "APK version counter regressed below verified release 1.6.1 / code 18. " +
                        "Recover the latest apk-version.properties from release history; do not reset it or lower this guard."
                }
                val code = Math.addExact(previousCode, 1)
                parts[2] = Math.addExact(parts[2].toInt(), 1).toString()
                val contents = "versionCode=$code\nversionName=${parts.joinToString(".")}\n"
                // Reserve first: failed builds can leave gaps, but never reuse a number.
                val pending = Files.createTempFile(counter.parentFile.toPath(), ".apk-version-", ".tmp")
                try {
                    Files.writeString(pending, contents)
                    Files.move(pending, counter.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(pending)
                }
                val output = versionFile.get().asFile
                output.parentFile.mkdirs()
                output.writeText(contents)
                logger.lifecycle("${name}: ${parts.joinToString(".")} (versionCode $code)")
            }
        }
    }
}

// Release signing comes from keystore.properties (gitignored). It points at a
// keystore and password file kept outside the repo.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "net.finleg.bridget"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.finleg.bridget"
        minSdk = 34
        targetSdk = 35
    }

    buildFeatures { buildConfig = true }
    flavorDimensions += "distribution"
    productFlavors {
        create("private") { dimension = "distribution"; buildConfigField("boolean", "PRIVATE_CONTROL", "true") }
        create("play") { dimension = "distribution"; applicationIdSuffix = ".play"; buildConfigField("boolean", "PRIVATE_CONTROL", "false") }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                val pw = file(keystoreProps.getProperty("passwordFile")).readText().trim()
                storePassword = pw
                keyPassword = pw
                keyAlias = keystoreProps.getProperty("keyAlias")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*"
            )
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val allocation = tasks.register<AllocateApkVersion>(
            "allocate${variant.name.replaceFirstChar { it.uppercaseChar() }}ApkVersion"
        ) {
            counterFile.set(rootProject.layout.projectDirectory.file("apk-version.properties"))
            lockFile.set(rootProject.layout.projectDirectory.file(".gradle/apk-version.lock"))
            versionFile.set(layout.buildDirectory.file("generated/apk-version/${variant.name}/version.properties"))
        }
        val version = allocation.flatMap { it.versionFile }.map { file ->
            Properties().apply { file.asFile.inputStream().use { load(it) } }
        }
        variant.outputs.forEach { output ->
            output.versionCode.set(version.map { it.getProperty("versionCode").toInt() })
            output.versionName.set(version.map { it.getProperty("versionName") })
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    val camerax = "1.4.1"
    val ktor = "2.3.12"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("com.google.zxing:core:3.5.3")
}
