plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.kotlinchat.server.MainKt")
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.logback.classic)
    implementation(libs.kaml)
    implementation(libs.bouncycastle.pkix)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
