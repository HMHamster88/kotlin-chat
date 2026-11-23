plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.kotlinchat.android"
    compileSdk = 35

    // Android only treats a different versionCode as an upgrade. Derive it from app.version so
    // "0.2.2" installs over "0.2.1" instead of the installer refusing or downgrading.
    val appVersion = providers.gradleProperty("app.version").get()
    val versionParts = appVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val versionCodeValue = (versionParts.getOrNull(0) ?: 0) * 10_000 +
        (versionParts.getOrNull(1) ?: 0) * 100 +
        (versionParts.getOrNull(2) ?: 0)

    defaultConfig {
        applicationId = "org.kotlinchat.android"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = appVersion
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

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME feeds the update check.
        buildConfig = true
    }

    signingConfigs {
        // One key for local builds and CI, so an APK from Releases updates a sideloaded app
        // instead of failing with INSTALL_FAILED_UPDATE_INCOMPATIBLE. This is the Android debug
        // key: public by design, and never to be used for a store listing.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":media-android"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
}
