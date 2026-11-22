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
        // Android is a JVM: Ktor CIO, the pinned trust manager and the TLS probe are identical.
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
            implementation(libs.ktor.client.cio)
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
