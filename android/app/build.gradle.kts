plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uk.octopuslive"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.octopuslive.app"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = 12
        versionName = "1.7.2"
    }

    // Release signing: uses a real keystore when the CI secrets are set (so updates install over
    // older versions).
    val keystoreFile = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed only when the CI keystore is present. Otherwise the release APK is left unsigned,
            // which is what F-Droid expects (it signs with its own key). For your own builds, use
            // the debug build (Android Studio's Run button), which is signed automatically.
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
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

// No third-party libraries: networking uses HttpURLConnection and JSON uses the built-in org.json.
dependencies {}
