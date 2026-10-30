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
        // Every build is numbered from git: versionCode = commits in HEAD's history, versionName = 1.6.<code>,
        // so a newer commit always installs over an older build. (Squashing history would lower the count:
        // pass -PbridgetVersionCode then.) -PbridgetVersionCode / -PbridgetVersionName still override, e.g. to
        // build an older-looking copy for testing the in-app update path (UPD-03/04).
        val gitCode = gitCommitCount()
        versionCode = (project.findProperty("bridgetVersionCode") as String?)?.toInt() ?: gitCode
        versionName = (project.findProperty("bridgetVersionName") as String?) ?: "1.6.$gitCode"
        buildConfigField("int", "DEFAULT_PORT", "8734")
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
        buildConfigField("boolean", "DIRTY_BUILD", "${gitDirty()}")
        // Crash reports (D32) go only where the builder points them: -PbridgitSentryDsn, BRIDGIT_SENTRY_DSN,
        // or sentryDsn in keystore.properties. Empty (the default, and every fork) means no crash reports.
        val sentryDsn = (project.findProperty("bridgitSentryDsn") as String?)
            ?: System.getenv("BRIDGIT_SENTRY_DSN") ?: keystoreProps.getProperty("sentryDsn") ?: ""
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.trim()}\"")
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
        // Wake words: Vosk's x86 builds only serve emulators (about 20 MB); there wake words report an error instead.
        jniLibs { excludes += setOf("lib/x86/libvosk.so", "lib/x86_64/libvosk.so") }
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

fun gitCommitCount(): Int =
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()

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
    // Email alerts: digests and retries survive restarts and wait for a network.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Crash reports (D32). Core only: no NDK, replay or Gradle plugin, so nothing is uploaded at build time.
    implementation("io.sentry:sentry-android-core:7.18.1")
    // Wake words (CAP-47): offline keyword spotting. The English model is downloaded on first use, not bundled.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
