plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// webrtc-java publishes its JNI library as classifier artifacts. The POM's own dependency on
// itself uses an unresolved Maven property, so transitives are disabled and natives are added
// explicitly. All platforms are included so the packaged app is portable.
val webrtcVersion = libs.versions.webrtcJava.get()

dependencies {
    api(project(":client-core"))

    implementation("dev.onvoid.webrtc:webrtc-java:$webrtcVersion") { isTransitive = false }
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcVersion:windows-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcVersion:linux-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcVersion:macos-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcVersion:macos-aarch64")

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("failed")
    }
}
