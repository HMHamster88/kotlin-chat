import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":client-core"))
    implementation(project(":media-desktop"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.logback.classic)
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
        )

        nativeDistributions {
            // Installers are M8; the portable app image (with kotlin-chat.exe) is what the
            // packageApp task ships for now.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "kotlin-chat"
            packageVersion = providers.gradleProperty("app.version").get()
            description = "Self-hosted chat with voice, screen sharing and peer-to-peer files"
            vendor = "kotlin-chat"
            copyright = "© kotlin-chat contributors"
        }
    }
}
