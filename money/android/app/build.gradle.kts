plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mykungfu.ledger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mykungfu.ledger"
        // Same floor as the deck log, so one phone can carry both.
        minSdk = 24
        targetSdk = 34
        // Stamped by CI: the run number always climbs, so Android treats each
        // build as a genuine upgrade rather than a reinstall of the same
        // version, and App Info shows which build is actually on the phone.
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = System.getenv("BUILD_NAME") ?: "dev"
    }

    // A fixed signing key, so every build carries the same signature and
    // Android accepts a new APK as an upgrade to the one already installed.
    //
    // Without this the Android plugin generates a debug key on the fly and a CI
    // runner starts with none, so every build is signed by a different
    // stranger. Android then refuses every upgrade and the only way in is to
    // uninstall first — which here would take every loan, holding and expense
    // with it. The deck log lost data to exactly this before it was caught,
    // and it is worse in this app.
    //
    // This is a DEBUG key with the conventional password. It is not a secret
    // and must never publish anything. A real release key comes through the
    // environment and is never committed.
    signingConfigs {
        getByName("debug") {
            storeFile     = file(System.getenv("SIGNING_STORE_FILE") ?: "../ledger-debug.keystore")
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
}
