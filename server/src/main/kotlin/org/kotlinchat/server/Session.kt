package org.kotlinchat.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.ErrorCode
import org.kotlinchat.protocol.ErrorPayload
import org.kotlinchat.protocol.Hello
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.Ping
import org.kotlinchat.protocol.Pong
import org.kotlinchat.protocol.Protocol
import org.kotlinchat.protocol.decodePayload
import org.kotlinchat.protocol.payloadOf
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("org.kotlinchat.server.Session")

/**
 * M0 session handler.
 *
 * Deliberately stateless: it validates framing, answers pings and echoes the hello
 * payload so the client stack can be proven end to end. M1 replaces this with the
 * SCRAM handshake and the real message router.
 */
internal suspend fun DefaultWebSocketServerSession.echoLoop() {
    val remote = call.request.local.remoteHost
    log.info("session opened from {}", remote)

    try {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> handleText(frame.readText())
                is Frame.Close -> return
                else -> Unit // binary frames are not part of the control channel yet
            }
        }
    } finally {
        log.info("session closed from {}", remote)
    }
}

private suspend fun DefaultWebSocketServerSession.handleText(text: String) {
    val envelope = try {
        Envelope.decode(text)
    } catch (e: Exception) {
        log.debug("rejected malformed envelope: {}", e.message)
        sendError(ErrorCode.BAD_PAYLOAD, "malformed envelope")
        return
    }

    if (envelope.v != Protocol.VERSION) {
        sendError(
            code = ErrorCode.PROTO_VERSION,
            message = "this server speaks protocol v${Protocol.VERSION}",
            fatal = true,
        )
        return
    }

    when (envelope.type) {
        MessageType.HELLO -> {
            val hello = try {
                envelope.decodePayload<Hello>()
            } catch (e: Exception) {
                sendError(ErrorCode.BAD_PAYLOAD, "invalid hello payload")
                return
            }
            if (hello.name.isBlank()) {
                sendError(ErrorCode.NAME_INVALID, "display name is required", fatal = true)
                return
            }
            log.info("hello from '{}' ({})", hello.name, remoteAddress())
            send(
                Frame.Text(
                    Envelope(
                        type = MessageType.ECHO,
                        replyTo = envelope.id,
                        payload = payloadOf(hello),
                    ).encode(),
                ),
            )
        }

        MessageType.PING -> {
            val ping = runCatching { envelope.decodePayload<Ping>() }.getOrDefault(Ping(0))
            send(
                Frame.Text(
                    Envelope(
                        type = MessageType.PONG,
                        replyTo = envelope.id,
                        payload = payloadOf(Pong(ts = ping.ts, serverTs = System.currentTimeMillis())),
                    ).encode(),
                ),
            )
        }

        else -> sendError(ErrorCode.BAD_PAYLOAD, "unsupported message type '${envelope.type}'")
    }
}

private suspend fun DefaultWebSocketServerSession.sendError(
    code: ErrorCode,
    message: String,
    fatal: Boolean = false,
) {
    send(
        Frame.Text(
            Envelope(
                type = MessageType.ERROR,
                payload = payloadOf(ErrorPayload(code = code, message = message, fatal = fatal)),
            ).encode(),
        ),
    )
    if (fatal) close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, code.name))
}

private fun DefaultWebSocketServerSession.remoteAddress(): String =
    "${call.request.local.remoteHost}:${call.request.local.remotePort}"
