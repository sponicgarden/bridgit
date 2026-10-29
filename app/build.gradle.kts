import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        // -PbridgetVersionCode / -PbridgetVersionName build an older-looking copy, used only to test
        // the in-app update path (UPD-03/04) against the published release. Never published.
        versionCode = (project.findProperty("bridgetVersionCode") as String?)?.toInt() ?: 18
        versionName = (project.findProperty("bridgetVersionName") as String?) ?: "1.6.0"
        buildConfigField("int", "DEFAULT_PORT", "8734")
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
        buildConfigField("boolean", "DIRTY_BUILD", "${gitDirty()}")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
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
        // ApiDocsTest writes build/apidocs/api.html for this version (tools/publish-apk.sh passes the APK's).
        unitTests.all { it.systemProperty("bridget.version", (project.findProperty("bridgetDocsVersion") as String?) ?: defaultConfig.versionName!!) }
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

dependencies {
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
