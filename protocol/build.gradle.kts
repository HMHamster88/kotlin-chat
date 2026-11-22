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
        // Android is a JVM: the crypto/JCE actuals are identical on both.
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        getByName("jvmSharedMain").dependencies {
            implementation(libs.bouncycastle.prov)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    compilerOptions {
        // expect/actual objects are how the crypto and media seams are expressed.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

android {
    namespace = "org.kotlinchat.protocol"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
