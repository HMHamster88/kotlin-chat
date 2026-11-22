plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

/**
 * Packaging for the current feature set: a portable desktop app image (it contains
 * `kotlin-chat.exe` on Windows) and an installable debug APK, both copied into `build/dist`.
 * Platform installers (MSI/dmg/deb) are M8.
 *
 *   ./gradlew packageApp
 */
val appVersion = providers.gradleProperty("app.version").getOrElse("0.0.0")
val hostOs = System.getProperty("os.name").lowercase().let { os ->
    when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        else -> "linux"
    }
}
val distDir = layout.buildDirectory.dir("dist")

val packageDesktop by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the portable desktop app image, including the launcher executable."

    dependsOn(":app-desktop:createDistributable")
    from(project(":app-desktop").layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("kotlin-chat-$appVersion-$hostOs.zip")
    destinationDirectory.set(distDir)
}

val packageAndroid by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the installable Android debug APK."

    dependsOn(":app-android:assembleDebug")
    // The file name follows the module name; take whichever APK the build produced.
    from(project(":app-android").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
    }
    into(distDir)
    rename { "kotlin-chat-$appVersion.apk" }
}

tasks.register("packageApp") {
    group = "distribution"
    description = "Builds the desktop app image and the Android APK into build/dist."
    dependsOn(packageDesktop, packageAndroid)
}
