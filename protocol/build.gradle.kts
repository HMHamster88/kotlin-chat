plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()

    compilerOptions {
        // expect/actual objects are how the crypto and media seams are expressed.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.prov)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
