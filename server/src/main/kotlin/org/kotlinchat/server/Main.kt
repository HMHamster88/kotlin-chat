package org.kotlinchat.server

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import org.kotlinchat.protocol.Protocol

fun main() {
    val host = System.getenv("CHAT_HOST") ?: "127.0.0.1"
    val port = System.getenv("CHAT_PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = host, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 45_000
        maxFrameSize = Protocol.MAX_FRAME_BYTES.toLong()
    }

    routing {
        get("/healthz") {
            call.respondText(
                """{"status":"ok","protocol":${Protocol.VERSION}}""",
                ContentType.Application.Json,
            )
        }
        webSocket("/ws") {
            echoLoop()
        }
    }
}
