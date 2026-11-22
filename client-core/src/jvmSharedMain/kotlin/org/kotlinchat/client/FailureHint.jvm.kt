package org.kotlinchat.client

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Maps the exception a connection attempt produced onto a short summary plus one concrete thing
 * to try. The raw text is kept as [ConnectionFailure.detail] so a bug report still has it.
 */
internal actual fun classifyConnectionFailure(
    t: Throwable,
    endpoint: ServerEndpoint,
): ConnectionFailure {
    val where = "${endpoint.host}:${endpoint.port}"
    val chain = t.causeChain()
    val messages = chain.mapNotNull { it.message }.joinToString(" | ").lowercase()
    val detail = chain.firstOrNull { !it.message.isNullOrBlank() }?.message ?: t::class.simpleName

    fun failure(summary: String, hint: String) = ConnectionFailure(summary, hint, detail)

    // Our own messages first: they are already precise about what the client rejected.
    return when {
        messages.contains("certificate changed") || messages.contains("does not match the pinned") ->
            failure(
                "server key changed",
                "Compare the new fingerprint with the one the server prints. If it really changed, " +
                    "use Forget server key, then connect again.",
            )

        messages.contains("password") || messages.contains("authentication failed") ->
            failure(
                "wrong server password",
                "Check the shared server password. Repeated failures are rate limited.",
            )

        messages.contains("signature mismatch") ->
            failure(
                "server could not prove the password",
                "The endpoint may be impersonating your server. Only continue if you trust the address.",
            )

        messages.contains("closed the connection during the handshake") ->
            failure(
                "server closed the connection",
                "Something answered on $where but did not finish the protocol: check the port " +
                    "and that it is a kotlin-chat server.",
            )

        messages.contains("rejected the handshake") ->
            failure("server refused this client", "Check the server version and the room limits.")

        chain.any { it is UnknownHostException } ->
            failure(
                "cannot resolve ${endpoint.host}",
                "Check the server address for typos, and that this machine can look it up.",
            )

        chain.any { it is SocketTimeoutException } || messages.contains("timed out") ->
            failure(
                "no answer from $where",
                "The host did not reply in time. Check the address, the firewall on both machines, " +
                    "and that the TLS switch matches the server.",
            )

        chain.any { it is ConnectException } || messages.contains("connection refused") ->
            failure(
                "nothing is listening on $where",
                "Start the server, check the port, and make sure both machines are on the same network.",
            )

        chain.any { it is NoRouteToHostException } ->
            failure("no route to $where", "Check the network, VPN or firewall between you and the server.")

        chain.any { it is CertificateException } ->
            failure(
                "certificate was rejected",
                "The server presented a key this client will not accept. Verify it out of band, then " +
                    "use Forget server key if the server was reinstalled.",
            )

        chain.any { it is SSLException } || messages.contains("ssl") || messages.contains("tls") ->
            failure(
                "TLS handshake failed",
                "This port did not speak TLS the way the client expects. Check the TLS switch and " +
                    "whether the port belongs to the chat server.",
            )

        else -> failure(
            t.message ?: t::class.simpleName ?: "connection failed",
            "The server could not be reached. Check the address, the server console, and the network.",
        )
    }
}

private fun Throwable.causeChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && chain.size < 12 && chain.none { it === current }) {
        chain += current
        current = current.cause
    }
    return chain
}
