package org.kotlinchat.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets

/** Per-platform Ktor engine; CIO on JVM, OkHttp on Android, Darwin later. */
internal expect fun httpEngine(): HttpClientEngine

internal fun newHttpClient(): HttpClient = HttpClient(httpEngine()) {
    install(WebSockets) {
        pingIntervalMillis = 20_000
    }
}
