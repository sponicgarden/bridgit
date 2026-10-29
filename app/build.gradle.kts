import java.util.Properties
import java.util.UUID

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Allocate at execution time, so sync/help/clean do not consume a version.
// Keep the counter outside build/ so `clean` cannot reset it.
@org.gradle.work.DisableCachingByDefault(because = "Each build needs a fresh APK version")
abstract class AllocateApkVersion : DefaultTask() {
    @get:Internal abstract val counterFile: RegularFileProperty
    @get:OutputFile abstract val versionFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache("APK allocation must use a unique invocation output")
    }

    @TaskAction
    fun allocate() {
        val command = listOf(
            "python3", project.rootProject.file("tools/allocate-apk-version.py").absolutePath,
            "--counter", counterFile.get().asFile.absolutePath,
            "--output", versionFile.get().asFile.absolutePath
        )
        val process = ProcessBuilder(command).directory(project.rootProject.projectDir)
            .redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "APK version allocation failed: $result" }
        logger.lifecycle(result.trim())
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
        buildConfigField("int", "DEFAULT_PORT", "8734")
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
        buildConfigField("boolean", "DIRTY_BUILD", "${gitDirty()}")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("private") { dimension="distribution"; buildConfigField("boolean","PRIVATE_CONTROL","true") }
        create("play") { dimension="distribution"; applicationIdSuffix=".play"; buildConfigField("boolean","PRIVATE_CONTROL","false") }
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
            applicationIdSuffix = (project.findProperty("bridgetApplicationIdSuffix") as String?) ?: ".debug"
            // Debug builds listen on 8735 so they never clash with a release build on 8734.
            buildConfigField("int", "DEFAULT_PORT", (project.findProperty("bridgetDebugPort") as String?) ?: "8735")
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
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/helperAssets"))
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // ApiDocsTest writes build/apidocs/api.html for this version (tools/publish-apk.sh passes the APK's).
        unitTests.all { it.systemProperty("bridget.version", (project.findProperty("bridgetDocsVersion") as String?) ?: "development") }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*"
            )
        }
    }
}

// GET /v1/tools/bridget serves the helper script. tools/bridgit is the single
// source of truth; this copies it into the APK's assets at build time.
val copyHelper by tasks.registering(Copy::class) {
    from(rootProject.file("tools/bridgit"))
    into(layout.buildDirectory.dir("generated/helperAssets"))
    rename { "bridget-helper.py" }
}
val writeProvenance by tasks.registering {
    val target = layout.buildDirectory.file("generated/helperAssets/build-provenance.json")
    val commit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
    val dirty = gitDirty()
    inputs.property("commit", commit)
    inputs.property("dirty", dirty)
    outputs.file(target)
    doLast {
        target.get().asFile.apply { parentFile.mkdirs(); writeText("""{"git_commit":"$commit","dirty_build":$dirty}""" + "\n") }
    }
}
tasks.named("preBuild") { dependsOn(copyHelper, writeProvenance) }

fun gitCommit(): String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

fun gitDirty(): Boolean = runCatching {
    providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get().isNotBlank()
}.getOrDefault(true)

// An immutable per-invocation file prevents another build in the same checkout
// from changing values while AGP reads this invocation’s manifest providers.
val apkVersionInvocation = UUID.randomUUID().toString()

androidComponents {
    onVariants(selector().all()) { variant ->
        val allocation = tasks.register<AllocateApkVersion>(
            "allocate${variant.name.replaceFirstChar { it.uppercaseChar() }}ApkVersion"
        ) {
            counterFile.set(rootProject.layout.projectDirectory.file("apk-version.properties"))
            versionFile.set(layout.buildDirectory.file("generated/apk-version/${variant.name}/$apkVersionInvocation/version.properties"))
        }
        val version = allocation.flatMap { it.versionFile }.map { file ->
            Properties().apply { file.asFile.inputStream().use { load(it) } }
        }
        variant.buildConfigFields.put("VERSION_NAME", version.map { com.android.build.api.variant.BuildConfigField("String", "\"${it.getProperty("versionName")}\"", "Allocated APK version") })
        variant.buildConfigFields.put("VERSION_CODE", version.map { com.android.build.api.variant.BuildConfigField("int", it.getProperty("versionCode"), "Allocated APK version") })
        variant.outputs.forEach { output ->
            output.versionCode.set(version.map { it.getProperty("versionCode").toInt() })
            output.versionName.set(version.map { it.getProperty("versionName") })
        }
    }
}

dependencies {
    testImplementation("org.robolectric:robolectric:4.14.1")
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
    implementation("androidx.camera:camera-video:$camerax")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // Crash reports (D32). Core only: no NDK, replay or Gradle plugin, so nothing is uploaded at build time.
    implementation("io.sentry:sentry-android-core:7.18.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
