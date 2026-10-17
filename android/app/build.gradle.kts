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
        versionCode = 6
        versionName = "1.5"
    }

    // Release signing: uses a real keystore when the CI secrets are set (so updates install over
    // older versions), otherwise falls back to the debug key so local builds just work.
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
            signingConfig = signingConfigs.getByName(if (keystoreFile != null) "release" else "debug")
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
