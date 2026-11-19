import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A key of its own, kept out of the repo (see README). The phone promotes whatever is signed
// with it to a privileged app with root, so it is not shared with anything else.
val signing = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.mukhtharcm.simbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.mukhtharcm.simbridge"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.4.0"
    }

    signingConfigs {
        if (signing.isNotEmpty()) create("own") {
            storeFile = rootProject.file("signing/release.jks")
            storePassword = signing.getProperty("password")
            keyAlias = "simbridge"
            keyPassword = signing.getProperty("password")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("own")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The API's WebSocket server. Android has none built in.
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    testImplementation("junit:junit:4.13.2")
}
