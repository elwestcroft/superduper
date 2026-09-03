import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing credentials live only in keystore.properties (gitignored) — never in
// this file, which is committed. Absent for anyone who clones the repo without it;
// assembleRelease then falls back to unsigned, which the fallback below makes explicit
// rather than a confusing Gradle error deep in the signing step.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.superduper.notes"
    // Device is API 30-locked (Supernote Nomad, Chauvet OS) — see SPEC.md §3 for the
    // min/target=30, compile=37 rationale.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.superduper.notes"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // The Nomad is an RK3566 (ARM). Shipping x86/x86_64 copies of
            // libink.so + libgraphics-core.so only inflates the sideloaded APK.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        // BuildConfig gates the debug-only adb control channel: a runtime receiver on
        // API 30 is implicitly exported, so it must not exist in a release build.
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // This app is never distributed through Play Store — it's a direct sideload to
        // one specific device (a locked-down API 30 tablet, per the targetSdk comment
        // above) — so Play's minimum-target-API policy doesn't apply and would otherwise
        // fail every release build for a "requirement" this app is not subject to.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // Deliberately NOT depended on (SPEC.md §0.4):
    //   androidx.ink            — the firmware engine supplies the stroke model, vector
    //                             erasers and .tch persistence that these modules exist for
    //   androidx.graphics-core  — front-buffered rendering was for an app-drawn wet stroke;
    //                             the engine's -15 priority FASTPW path made it moot
    //   input-motionprediction  — the engine owns the wet path
}
