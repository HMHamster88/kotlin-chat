package org.kotlinchat.client

/** Where to connect and how. */
data class ServerEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean = true,
) {
    val key: String get() = "$host:$port"

    val url: String get() = "${if (tls) "wss" else "ws"}://$host:$port/ws"

    val label: String get() = "${if (tls) "wss" else "ws"}://$host:$port"
}
