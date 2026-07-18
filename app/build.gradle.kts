plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// App display name — single source of truth lives in gradle.properties (`appName`).
val appName = providers.gradleProperty("appName").get()

android {
    namespace = "com.timbra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.timbra"
        minSdk = 24
        targetSdk = 35
        // Bump both on EVERY change (see CLAUDE.md). versionName is surfaced in the
        // app (Library toolbar subtitle) and in the output APK filename.
        versionCode = 75
        versionName = "0.6.10"

        // Generate R.string.app_name from `appName` so the name isn't duplicated in strings.xml.
        resValue("string", "app_name", appName)
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("key.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Strip unused code + resources (e.g. the many unused matte_* assets).
            isMinifyEnabled = true
            isShrinkResources = true
            // Debug key so the release APK installs directly via adb (no keystore setup).
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Compress the FFmpeg .so files in the APK (extracted at install) instead of
            // AGP's default uncompressed packaging — roughly halves the download size.
            useLegacyPackaging = true
            // The FFmpeg libs ship prebuilt in the nextlib AAR for all ABIs; ndk.abiFilters
            // doesn't strip dependency jniLibs, so drop the emulator-only x86 ABIs here.
            excludes += listOf("**/x86/**", "**/x86_64/**")
        }
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // FFmpeg-backed decoders for Media3 (wide format support, all ABIs incl. arm64).
    implementation("com.github.anilbeesetti.nextlib:nextlib-media3ext:0.8.4")

    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.guava:guava:33.3.1-android")
}
