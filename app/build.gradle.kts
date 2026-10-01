// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appName = providers.gradleProperty("appName").get().trim()
require(appName.isNotEmpty()) { "appName in gradle.properties must not be blank" }

require(appName.matches(Regex("[A-Za-z0-9][A-Za-z0-9 ._-]*"))) {
    "appName must be letters, digits, spaces, dots, underscores or hyphens (was: \"$appName\")"
}

android {
    namespace = "com.timbra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.timbra"
        minSdk = 24
        targetSdk = 35
        versionCode = 139
        versionName = "0.9.17"

        resValue("string", "app_name", appName)

        resourceConfigurations += "en"

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("timbra.keystore")
            storePassword = "timbra"
            keyAlias = "timbra"
            keyPassword = "timbra"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("**/x86/**", "**/x86_64/**")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.guava:guava:33.3.1-android")
}
