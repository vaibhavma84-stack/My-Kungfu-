plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gasplanet.grabber"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gasplanet.grabber"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = System.getenv("BUILD_NAME") ?: "dev"

        // The yt-dlp engine ships a native Python runtime per architecture and
        // each one costs about 25 MB of APK. Every Android phone sold since
        // roughly 2016 is arm64, so shipping only that halves the download.
        // Add "armeabi-v7a" here if a genuinely old 32-bit phone needs it.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // The engine does not run its Python and ffmpeg binaries from inside the
    // APK -- it copies them out of the native library directory on first
    // launch. That only works if the libraries are extracted at install time,
    // which is what legacy packaging means. Without this the app installs
    // fine and then fails at startup with "library not found".
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    // A fixed key, so each new APK installs as an upgrade over the last one
    // instead of Android refusing it as a different app. This is a debug key
    // carrying Android's published default password -- it is not a secret and
    // must never be used to publish to a store.
    signingConfigs {
        getByName("debug") {
            storeFile     = file(System.getenv("SIGNING_STORE_FILE") ?: "../grabber-debug.keystore")
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        // Pinned to the Compose compiler that matches Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // The download engine: yt-dlp with a bundled Python runtime, plus ffmpeg
    // for joining separate video and audio streams back together.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
