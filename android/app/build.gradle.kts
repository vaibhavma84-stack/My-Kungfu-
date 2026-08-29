plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gasplanet.decklog"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gasplanet.decklog"
        // Samsung A55 runs Android 14; 24 keeps older phones aboard working too.
        minSdk = 24
        targetSdk = 34
        // Stamped by CI: the run number always climbs, so Android treats each
        // build as a genuine upgrade rather than a reinstall of the same
        // version, and App Info shows which build is actually on the phone.
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = System.getenv("BUILD_NAME") ?: "dev"
    }

    // A fixed signing key, so every build carries the same signature and Android
    // accepts a new APK as an upgrade to the one already on the phone.
    //
    // Without this the Android plugin generates a debug key on the fly, and a
    // CI runner starts with none — so every build was signed by a different
    // stranger, Android refused every upgrade, and the only way to install was
    // to uninstall first, which wipes all the data the app holds.
    //
    // This is a DEBUG key carrying Android's published default password. It is
    // not a secret and must never be used to publish anything. A real release
    // key is supplied through the environment instead, and never committed.
    signingConfigs {
        getByName("debug") {
            storeFile     = file(System.getenv("SIGNING_STORE_FILE") ?: "../decklog-debug.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "android"
            keyAlias      = System.getenv("SIGNING_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword   = System.getenv("SIGNING_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
}
