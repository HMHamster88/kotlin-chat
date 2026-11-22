package org.kotlinchat.server.core

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.kotlinchat.protocol.AuthRequest
import org.kotlinchat.protocol.Challenge
import org.kotlinchat.protocol.ChatSend
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.ErrorCode
import org.kotlinchat.protocol.ErrorPayload
import org.kotlinchat.protocol.Hello
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.Ping
import org.kotlinchat.protocol.Pong
import org.kotlinchat.protocol.PresenceUpdate
import org.kotlinchat.protocol.Protocol
import org.kotlinchat.protocol.RoomJoinRequest
import org.kotlinchat.protocol.RoomListResult
import org.kotlinchat.protocol.ServerLimits
import org.kotlinchat.protocol.TurnCredentials
import org.kotlinchat.protocol.Welcome
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.Scram
import org.kotlinchat.protocol.crypto.toFingerprint
import org.kotlinchat.protocol.crypto.toHex
import org.kotlinchat.protocol.decodePayload
import org.kotlinchat.protocol.payloadOf
import org.kotlinchat.server.auth.PasswordVerifier
import org.kotlinchat.server.config.ServerConfig
import org.slf4j.LoggerFactory
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The control plane: SCRAM handshake, room membership, presence and text relay.
 * Media never reaches this process.
 */
class ChatServer(
    val config: ServerConfig,
    val verifier: PasswordVerifier,
    val state: ChatState = ChatState(config),
    private val throttle: AuthThrottle = AuthThrottle(),
) {
    private val log = LoggerFactory.getLogger(ChatServer::class.java)

    fun install(app: Application) {
        app.install(WebSockets) {
            pingPeriodMillis = 20_000
            timeoutMillis = 45_000
            maxFrameSize = Protocol.MAX_FRAME_BYTES.toLong()
        }

        app.routing {
            get("/healthz") {
                val sessions = state.sessionCount()
                call.respondText(
                    """{"status":"ok","protocol":${Protocol.VERSION},"sessions":$sessions}""",
                    ContentType.Application.Json,
                )
            }

            webSocket("/ws") {
                handleConnection()
            }
        }
    }

    // ------------------------------------------------------------------ transport

    private suspend fun DefaultWebSocketServerSession.handleConnection() {
        val remoteHost = call.request.local.remoteHost
        val session = ClientSession(
            id = "s_" + Crypto.randomBytes(6).toHex(),
            remoteHost = remoteHost,
        )
        log.info("connection from {} as {}", remoteHost, session.id)

        coroutineScope {
            val writer = launch {
                try {
                    for (text in session.outgoing) send(Frame.Text(text))
                } catch (_: Throwable) {
                    // peer went away; the read loop observes the closed socket
                }
            }

            try {
                readLoop(session)
            } finally {
                withContext(NonCancellable) {
                    if (session.authenticated) {
                        state.unregister(session)
                        log.info("session ended {} ({})", session.name, session.id)
                    }
                    session.outgoing.close()
                    withTimeoutOrNull(500) { writer.join() }
                }
            }
        }
    }

    private suspend fun DefaultWebSocketServerSession.readLoop(session: ClientSession) {
        var handshake: Handshake? = null

        for (frame in incoming) {
            if (frame !is Frame.Text) continue

            val envelope = try {
                Envelope.decode(frame.readText())
            } catch (e: Exception) {
                sendError(session, ErrorCode.BAD_PAYLOAD, "malformed envelope")
                continue
            }

            if (envelope.v != Protocol.VERSION) {
                sendError(
                    session,
                    ErrorCode.PROTO_VERSION,
                    "this server speaks protocol v${Protocol.VERSION}",
                    fatal = true,
                )
                return
            }

            val keepGoing = when (envelope.type) {
                MessageType.HELLO -> {
                    if (session.authenticated) {
                        sendError(session, ErrorCode.BAD_PAYLOAD, "already authenticated")
                    } else {
                        handshake = startHandshake(session, envelope)
                    }
                    true
                }

                MessageType.AUTH -> authenticate(session, handshake, envelope)

                MessageType.PING -> {
                    val ping = runCatching { envelope.decodePayload<Ping>() }.getOrNull()
                    if (ping != null) {
                        session.send(
                            MessageType.PONG,
                            payloadOf(Pong(ts = ping.ts, serverTs = System.currentTimeMillis())),
                            replyTo = envelope.id,
                        )
                    }
                    true
                }

                else -> if (!session.authenticated) {
                    sendError(session, ErrorCode.AUTH_REQUIRED, "authenticate first", fatal = true)
                    false
                } else {
                    handleAuthenticated(session, envelope)
                }
            }

            if (!keepGoing) return
        }
    }

    // ------------------------------------------------------------------ handshake

    private class Handshake(
        val name: String,
        val pubkeyFp: String?,
        val serverNonce: ByteArray,
    )

    private fun startHandshake(session: ClientSession, envelope: Envelope): Handshake? {
        val hello = runCatching { envelope.decodePayload<Hello>() }.getOrNull()
        if (hello == null) {
            sendError(session, ErrorCode.BAD_PAYLOAD, "invalid hello payload")
            return null
        }

        val name = sanitizeName(hello.name)
        if (name == null) {
            sendError(session, ErrorCode.NAME_INVALID, "display name must be 1-32 printable characters", fatal = true)
            return null
        }

        val pubkeyFp = hello.pubkey?.let { encoded ->
            runCatching { Crypto.sha256(Crypto.base64Decode(encoded)).toFingerprint() }.getOrNull()
        }

        val nonce = Crypto.randomBytes(16)
        session.send(
            MessageType.CHALLENGE,
            payloadOf(Challenge(nonce = Crypto.base64Encode(nonce), kdf = verifier.kdf)),
            replyTo = envelope.id,
        )
        return Handshake(name = name, pubkeyFp = pubkeyFp, serverNonce = nonce)
    }

    /** Returns false when the connection must be closed. */
    private suspend fun authenticate(
        session: ClientSession,
        handshake: Handshake?,
        envelope: Envelope,
    ): Boolean {
        if (session.authenticated) {
            sendError(session, ErrorCode.BAD_PAYLOAD, "already authenticated")
            return true
        }
        if (handshake == null) {
            sendError(session, ErrorCode.AUTH_REQUIRED, "hello first", fatal = true)
            return false
        }
        if (throttle.isLocked(session.remoteHost)) {
            sendError(session, ErrorCode.AUTH_RATE_LIMIT, "too many failed attempts, try later", fatal = true)
            return false
        }

        val request = runCatching { envelope.decodePayload<AuthRequest>() }.getOrNull()
        val clientNonce = request?.let { runCatching { Crypto.base64Decode(it.clientNonce) }.getOrNull() }
        val proof = request?.let { runCatching { Crypto.base64Decode(it.proof) }.getOrNull() }
        if (request == null || clientNonce == null || proof == null) {
            sendError(session, ErrorCode.BAD_PAYLOAD, "invalid auth payload")
            return false
        }

        val authMessage = Scram.authMessage(handshake.name, handshake.serverNonce, clientNonce)
        if (!Scram.verifyProof(proof, verifier.storedKey, authMessage)) {
            throttle.recordFailure(session.remoteHost)
            log.info("authentication failed for '{}' from {}", handshake.name, session.remoteHost)
            sendError(session, ErrorCode.AUTH_FAILED, "invalid server password", fatal = true)
            return false
        }

        throttle.recordSuccess(session.remoteHost)

        state.register(session, handshake.name, handshake.pubkeyFp) { initialState, rooms ->
            val welcome = Welcome(
                sessionId = session.id,
                serverName = config.serverName,
                motd = config.motd,
                serverSignature = Crypto.base64Encode(Scram.serverSignature(verifier.serverKey, authMessage)),
                rooms = rooms,
                turn = turnCredentials(session.id),
                limits = ServerLimits(
                    maxChatBytes = config.maxChatBytes,
                    maxMembersPerRoom = config.maxUsersPerRoom,
                ),
            )
            session.send(MessageType.WELCOME, payloadOf(welcome), replyTo = envelope.id)
            session.send(MessageType.ROOM_STATE, payloadOf(initialState))
        }

        log.info("session started {} ({})", session.name, session.id)
        return true
    }

    // ------------------------------------------------------------------ session

    private suspend fun handleAuthenticated(session: ClientSession, envelope: Envelope): Boolean {
        when (envelope.type) {
            MessageType.ROOM_LIST ->
                session.send(
                    MessageType.ROOM_LIST_RESULT,
                    payloadOf(RoomListResult(state.roomsSummary())),
                    replyTo = envelope.id,
                )

            MessageType.ROOM_JOIN -> {
                if (!session.roomChangeLimiter.tryAcquire()) {
                    sendError(session, ErrorCode.RATE_LIMIT, "slow down")
                    return true
                }
                val request = runCatching { envelope.decodePayload<RoomJoinRequest>() }.getOrNull()
                if (request == null) {
                    sendError(session, ErrorCode.BAD_PAYLOAD, "invalid room.join payload")
                    return true
                }
                when (
                    state.join(session, request.roomId) { newState ->
                        session.send(MessageType.ROOM_STATE, payloadOf(newState), replyTo = envelope.id)
                    }
                ) {
                    JoinResult.Ok -> Unit
                    JoinResult.UnknownRoom ->
                        sendError(session, ErrorCode.ROOM_NOT_FOUND, "no such room")
                    JoinResult.Full ->
                        sendError(session, ErrorCode.ROOM_FULL, "room is full")
                }
            }

            MessageType.ROOM_LEAVE ->
                when (
                    state.join(session, ClientSession.LOBBY) { newState ->
                        session.send(MessageType.ROOM_STATE, payloadOf(newState), replyTo = envelope.id)
                    }
                ) {
                    JoinResult.Ok -> Unit
                    else -> sendError(session, ErrorCode.INTERNAL, "cannot return to lobby")
                }

            MessageType.CHAT_SEND -> {
                val request = runCatching { envelope.decodePayload<ChatSend>() }.getOrNull()
                if (request == null) {
                    sendError(session, ErrorCode.BAD_PAYLOAD, "invalid chat.send payload")
                    return true
                }
                when (state.postChat(session, request.text, request.clientMsgId)) {
                    is ChatResult.Ok -> Unit // already broadcast
                    ChatResult.RateLimited ->
                        sendError(session, ErrorCode.RATE_LIMIT, "chat rate limit exceeded")
                    ChatResult.Invalid ->
                        sendError(
                            session,
                            ErrorCode.BAD_PAYLOAD,
                            "message must be 1..${config.maxChatBytes} bytes",
                        )
                }
            }

            MessageType.PRESENCE_UPDATE -> {
                val request = runCatching { envelope.decodePayload<PresenceUpdate>() }.getOrNull()
                if (request == null) {
                    sendError(session, ErrorCode.BAD_PAYLOAD, "invalid presence.update payload")
                    return true
                }
                state.updatePresence(
                    session = session,
                    selfMute = request.selfMute,
                    selfDeaf = request.selfDeaf,
                    sharing = request.sharing,
                )
            }

            else -> sendError(session, ErrorCode.BAD_PAYLOAD, "unsupported message type '${envelope.type}'")
        }
        return true
    }

    // ------------------------------------------------------------------ helpers

    private fun sendError(
        session: ClientSession,
        code: ErrorCode,
        message: String,
        fatal: Boolean = false,
    ) {
        session.send(
            MessageType.ERROR,
            payloadOf(ErrorPayload(code = code, message = message, fatal = fatal)),
        )
        if (fatal) session.outgoing.close()
    }

    private fun turnCredentials(sessionId: String): TurnCredentials? {
        val turn = config.turn ?: return null
        val expiry = System.currentTimeMillis() / 1000 + turn.ttlSeconds
        val username = "$expiry:$sessionId"
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(turn.secret.toByteArray(), "HmacSHA1"))
        }
        val credential = Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray()))
        return TurnCredentials(
            urls = turn.urls,
            username = username,
            credential = credential,
            ttlSeconds = turn.ttlSeconds,
        )
    }

    private fun sanitizeName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 32) return null
        if (name.any { it.isISOControl() }) return null
        return name
    }
}
