plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mykungfu.mvtagger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mykungfu.mvtagger"
        // 24 is where the Storage Access Framework behaves consistently enough
        // to rely on for renaming and creating documents.
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = System.getenv("BUILD_NAME") ?: "dev"
    }

    // A fixed debug key, for the same reason the other app in this repo has one:
    // without it every CI build is signed by a different generated key, Android
    // refuses the upgrade, and the only way to install is to uninstall first --
    // which throws away the library and every setting with it.
    //
    // This is a DEBUG key carrying Android's published default password. It is
    // not a secret and must never be used to publish anything.
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "../mvtagger-debug.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "android"
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

    buildFeatures {
        compose = true
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
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
