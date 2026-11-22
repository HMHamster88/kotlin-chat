plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(project(":protocol"))
    testImplementation(project(":client-core"))
    testImplementation(project(":server"))

    testImplementation(kotlin("test"))
    testImplementation(project(":media-desktop"))
    testImplementation(libs.logback.classic)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("failed")
    }
}
