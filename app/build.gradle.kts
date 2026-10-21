plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.waterfall"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.waterfall"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // One committed sideload key, so anyone's build can update anyone else's install in place.
    // It signs nothing secret: this is a sideloaded app, not a Play Store release.
    signingConfigs {
        create("sideload") {
            storeFile = file("waterfall.keystore")
            storePassword = "waterfall"
            keyAlias = "waterfall"
            keyPassword = "waterfall"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("sideload") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
