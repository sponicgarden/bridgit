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
        versionCode = 2
        versionName = "1.1.0"
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*"
            )
        }
    }
}

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
}
