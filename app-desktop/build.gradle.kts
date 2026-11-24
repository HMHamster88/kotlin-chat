import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

// The running app needs its own version for the update check. jpackage knows it, but it is not
// exposed at runtime, so pass the same Gradle property through as a system property. `gradlew run`
// gets it too, which keeps the update banner testable outside a packaged build.
val appVersion = providers.gradleProperty("app.version").get()

dependencies {
    implementation(project(":protocol"))
    implementation(project(":client-core"))
    implementation(project(":media-desktop"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.logback.classic)
    // Used by the updater to stream a release archive while hashing it.
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "org.kotlinchat.desktop.MainKt"

        // The default JVM sizes its heap from the machine's RAM (a 32 GB box reserves ~8 GB of
        // address space and commits 500 MB up front) and spins up GC/JIT threads per core. A chat
        // client needs none of that: cap the heap, keep the thread pools small, and let the heap
        // shrink back after spikes.
        jvmArgs += listOf(
            "-Xms32m",
            "-Xmx320m",
            "-XX:MaxMetaspaceSize=192m",
            "-XX:ReservedCodeCacheSize=96m",
            "-XX:MaxDirectMemorySize=128m",
            "-XX:ActiveProcessorCount=4",
            "-XX:MinHeapFreeRatio=5",
            "-XX:MaxHeapFreeRatio=20",
            "-Dkotlinchat.version=$appVersion",
        )

        nativeDistributions {
            // Installers are M8; the portable app image (with kotlin-chat.exe) is what the
            // packageApp task ships for now.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "kotlin-chat"
            packageVersion = appVersion
            description = "Self-hosted chat with voice, screen sharing and peer-to-peer files"
            vendor = "Kotlin Chat"
            copyright = "© Kotlin Chat contributors"

            // jpackage wants a native icon for the host platform, and the DSL takes one per
            // platform: the multi-size .ico on Windows, the PNG on Linux and the .icns on macOS.
            val hostOs = System.getProperty("os.name").lowercase()
            when {
                hostOs.contains("win") -> windows { iconFile.set(project.file("icons/kotlin-chat.ico")) }
                hostOs.contains("mac") || hostOs.contains("darwin") ->
                    macOS { iconFile.set(project.file("icons/kotlin-chat.icns")) }
                else -> linux { iconFile.set(project.file("icons/kotlin-chat.png")) }
            }
        }
    }
}
