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
        versionCode = (project.findProperty("bridgetVersionCode") as String?)?.toInt() ?: 9
        versionName = (project.findProperty("bridgetVersionName") as String?) ?: "1.4.4"
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
            applicationIdSuffix = ".debug"
            // Debug builds listen on 8735 so they never clash with a release build on 8734.
            buildConfigField("int", "DEFAULT_PORT", "8735")
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
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*"
            )
        }
    }
}

// GET /v1/tools/bridget serves the helper script. tools/bridget is the single
// source of truth; this copies it into the APK's assets at build time.
val copyHelper by tasks.registering(Copy::class) {
    from(rootProject.file("tools/bridget"))
    into(layout.buildDirectory.dir("generated/helperAssets"))
    rename { "bridget-helper.py" }
}
tasks.named("preBuild") { dependsOn(copyHelper) }

fun gitCommit(): String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

fun gitDirty(): Boolean = runCatching {
    providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get().isNotBlank()
}.getOrDefault(false)

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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
