plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        // Android is a JVM: the same Ktor engine, pinned trust manager and TLS probe apply.
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api(project(":protocol"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        getByName("jvmSharedMain").dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        getByName("jvmMain").dependencies {
            // DPAPI for the remembered password on Windows; other systems use their own CLI.
            implementation(libs.jna.platform)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "org.kotlinchat.client"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
