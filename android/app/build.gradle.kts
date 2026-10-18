plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "uk.octopuslive"
    compileSdk = 36   // needed by the AppFunctions library (Gemini integration)

    defaultConfig {
        applicationId = "uk.octopuslive.app"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = 12
        versionName = "1.8.0"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Networking uses HttpURLConnection and JSON uses the built-in org.json.
// The only libraries are Google's AppFunctions (lets Gemini ask the app for your usage on
// Android 16+) and Kotlin coroutines, which it needs.
dependencies {
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha13")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// Also write the older (v1) function list, which Android 16 phones read.
ksp {
    arg("appfunctions:generateV1Xml", "true")
}
