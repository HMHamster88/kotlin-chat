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
import org.kotlinchat.server.core.ChatServer
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): Envelope {
    val frame = incoming.receive()
    check(frame is Frame.Text) { "expected text frame, got $frame" }
    return Envelope.decode(frame.readText())
}
