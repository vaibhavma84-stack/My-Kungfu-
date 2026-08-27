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

    buildTypes {
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
