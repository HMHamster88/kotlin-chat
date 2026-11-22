package org.kotlinchat.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.kotlinchat.protocol.Challenge
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.ErrorCode
import org.kotlinchat.protocol.ErrorPayload
import org.kotlinchat.protocol.Hello
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.Welcome
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.Scram
import org.kotlinchat.protocol.decodePayload
import org.kotlinchat.protocol.payloadOf
import org.kotlinchat.server.auth.PasswordVerifier
import org.kotlinchat.server.config.ServerConfig
import org.kotlinchat.server.config.TurnConfig
import org.kotlinchat.server.core.ChatServer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full handshake over the Ktor test engine, with the SCRAM math driven explicitly so a
 * regression in either side of the exchange fails here.
 */
class HandshakeTest {

    private val password = "correct horse battery staple"
    private val verifier = PasswordVerifier.create(password, memoryKib = 16, iterations = 1)
    private val config = ServerConfig(serverName = "test", tls = org.kotlinchat.server.config.TlsConfig(enabled = false))

    @Test
    fun `correct password reaches welcome and lobby state`() = testApplication {
        val server = ChatServer(config, verifier)
        application { server.install(this) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            val hello = Hello(name = "alice")
            send(Frame.Text(Envelope.of(MessageType.HELLO, id = "h1", payload = payloadOf(hello)).encode()))

            val challenge = receiveEnvelope()
            assertEquals(MessageType.CHALLENGE, challenge.type)
            val challengePayload = challenge.decodePayload<Challenge>()
            val serverNonce = Crypto.base64Decode(challengePayload.nonce)

            val clientNonce = Crypto.randomBytes(16)
            val keys = Scram.keys(Scram.deriveAuthKey(password, challengePayload.kdf))
            val authMessage = Scram.authMessage("alice", serverNonce, clientNonce)

            send(
                Frame.Text(
                    Envelope.of(
                        MessageType.AUTH,
                        id = "h2",
                        payload = payloadOf(
                            org.kotlinchat.protocol.AuthRequest(
                                clientNonce = Crypto.base64Encode(clientNonce),
                                proof = Crypto.base64Encode(Scram.clientProof(keys, authMessage)),
                            ),
                        ),
                    ).encode(),
                ),
            )

            val welcomeEnvelope = receiveEnvelope()
            assertEquals(MessageType.WELCOME, welcomeEnvelope.type)
            val welcome = welcomeEnvelope.decodePayload<Welcome>()
            assertEquals("test", welcome.serverName)
            assertTrue(welcome.sessionId.startsWith("s_"))

            val expectedSignature = Scram.serverSignature(keys.serverKey, authMessage)
            assertTrue(
                expectedSignature.contentEquals(Crypto.base64Decode(welcome.serverSignature!!)),
                "server signature must match the client's expectation",
            )

            val stateEnvelope = receiveEnvelope()
            assertEquals(MessageType.ROOM_STATE, stateEnvelope.type)
            val state = stateEnvelope.decodePayload<RoomState>()
            assertEquals("lobby", state.room.id)
            assertEquals("alice", state.self.name)
            assertEquals(1, state.members.size)
        }
    }

    @Test
    fun `wrong password is refused and closes the connection`() = testApplication {
        val server = ChatServer(config, verifier)
        application { server.install(this) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            val hello = Hello(name = "mallory")
            send(Frame.Text(Envelope.of(MessageType.HELLO, payload = payloadOf(hello)).encode()))

            val challenge = receiveEnvelope().decodePayload<Challenge>()
            val serverNonce = Crypto.base64Decode(challenge.nonce)
            val clientNonce = Crypto.randomBytes(16)
            val keys = Scram.keys(Scram.deriveAuthKey("wrong password", challenge.kdf))
            val authMessage = Scram.authMessage("mallory", serverNonce, clientNonce)

            send(
                Frame.Text(
                    Envelope.of(
                        MessageType.AUTH,
                        payload = payloadOf(
                            org.kotlinchat.protocol.AuthRequest(
                                clientNonce = Crypto.base64Encode(clientNonce),
                                proof = Crypto.base64Encode(Scram.clientProof(keys, authMessage)),
                            ),
                        ),
                    ).encode(),
                ),
            )

            val error = receiveEnvelope()
            assertEquals(MessageType.ERROR, error.type)
            val payload = error.decodePayload<ErrorPayload>()
            assertEquals(ErrorCode.AUTH_FAILED, payload.code)
            assertTrue(payload.fatal)
        }
    }

    @Test
    fun `messages before authentication are refused`() = testApplication {
        val server = ChatServer(config, verifier)
        application { server.install(this) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(Frame.Text(Envelope.of(MessageType.CHAT_SEND, payload = payloadOf(org.kotlinchat.protocol.ChatSend("hi"))).encode()))
            val error = receiveEnvelope()
            assertEquals(ErrorCode.AUTH_REQUIRED, error.decodePayload<ErrorPayload>().code)
        }
    }
    @Test
    fun `welcome carries usable TURN credentials when configured`() = testApplication {
        val secret = "turn-shared-secret"
        val turnConfig = config.copy(
            turn = TurnConfig(
                urls = listOf("turn:example.org:3478?transport=udp"),
                secret = secret,
                ttlSeconds = 3600,
            ),
        )
        val server = ChatServer(turnConfig, verifier)
        application { server.install(this) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            val welcome = performHandshake("alice", password)
            val turn = assertNotNull(welcome.turn)
            assertEquals(listOf("turn:example.org:3478?transport=udp"), turn.urls)

            val (expiry, sessionId) = turn.username.split(":", limit = 2)
            assertTrue(sessionId.startsWith("s_"), "username was ${turn.username}")
            assertTrue(expiry.toLong() > System.currentTimeMillis() / 1000, "credential already expired")

            // coturn's REST auth: base64(HMAC-SHA1(secret, username)).
            val mac = Mac.getInstance("HmacSHA1").apply {
                init(SecretKeySpec(secret.toByteArray(), "HmacSHA1"))
            }
            val expected = Base64.getEncoder().encodeToString(mac.doFinal(turn.username.toByteArray()))
            assertEquals(expected, turn.credential)
        }
    }
}

private suspend fun DefaultClientWebSocketSession.performHandshake(
    name: String,
    password: String,
): Welcome {
    send(Frame.Text(Envelope.of(MessageType.HELLO, payload = payloadOf(Hello(name = name))).encode()))

    val challenge = receiveEnvelope().decodePayload<Challenge>()
    val serverNonce = Crypto.base64Decode(challenge.nonce)
    val clientNonce = Crypto.randomBytes(16)
    val keys = Scram.keys(Scram.deriveAuthKey(password, challenge.kdf))
    val authMessage = Scram.authMessage(name, serverNonce, clientNonce)

    send(
        Frame.Text(
            Envelope.of(
                MessageType.AUTH,
                payload = payloadOf(
                    org.kotlinchat.protocol.AuthRequest(
                        clientNonce = Crypto.base64Encode(clientNonce),
                        proof = Crypto.base64Encode(Scram.clientProof(keys, authMessage)),
                    ),
                ),
            ).encode(),
        ),
    )

    val envelope = receiveEnvelope()
    check(envelope.type == MessageType.WELCOME) { "expected welcome, got ${envelope.type}" }
    val welcome = envelope.decodePayload<Welcome>()
    val expectedSignature = Scram.serverSignature(keys.serverKey, authMessage)
    check(expectedSignature.contentEquals(Crypto.base64Decode(welcome.serverSignature!!))) {
        "server signature mismatch"
    }
    return welcome
}

private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): Envelope {
    val frame = incoming.receive()
    check(frame is Frame.Text) { "expected text frame, got $frame" }
    return Envelope.decode(frame.readText())
}
