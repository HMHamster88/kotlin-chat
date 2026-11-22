package org.kotlinchat.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.ErrorCode
import org.kotlinchat.protocol.ErrorPayload
import org.kotlinchat.protocol.Hello
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.Ping
import org.kotlinchat.protocol.Pong
import org.kotlinchat.protocol.decodePayload
import org.kotlinchat.protocol.payloadOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTest {

    @Test
    fun `echoes a hello envelope back to the sender`() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            val hello = Hello(name = "alice", pubkey = "AAAA")
            send(Frame.Text(Envelope.of(MessageType.HELLO, id = "c-1", payload = payloadOf(hello)).encode()))

            val reply = receiveEnvelope()
            assertEquals(MessageType.ECHO, reply.type)
            assertEquals("c-1", reply.replyTo)
            assertEquals(hello, reply.decodePayload<Hello>())
        }
    }

    @Test
    fun `answers ping with pong`() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(Frame.Text(Envelope.of(MessageType.PING, id = "c-2", payload = payloadOf(Ping(1234))).encode()))

            val reply = receiveEnvelope()
            assertEquals(MessageType.PONG, reply.type)
            assertEquals("c-2", reply.replyTo)
            val pong = reply.decodePayload<Pong>()
            assertEquals(1234, pong.ts)
            assertTrue(pong.serverTs > 0)
        }
    }

    @Test
    fun `rejects an unsupported protocol version`() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            val bad = Envelope(v = 99, type = MessageType.HELLO, payload = payloadOf(Hello(name = "bob")))
            send(Frame.Text(bad.encode()))

            val reply = receiveEnvelope()
            assertEquals(MessageType.ERROR, reply.type)
            val error = reply.decodePayload<ErrorPayload>()
            assertEquals(ErrorCode.PROTO_VERSION, error.code)
            assertTrue(error.fatal)
        }
    }

    @Test
    fun `reports malformed json as bad payload`() = testApplication {
        application { module() }
        val client = createClient { install(WebSockets) }

        client.webSocket("/ws") {
            send(Frame.Text("{not json"))

            val reply = receiveEnvelope()
            assertEquals(MessageType.ERROR, reply.type)
            assertEquals(ErrorCode.BAD_PAYLOAD, reply.decodePayload<ErrorPayload>().code)
        }
    }

    @Test
    fun `health endpoint is up`() = testApplication {
        application { module() }
        val client = createClient { }

        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\""))
    }
}

private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): Envelope {
    val frame = incoming.receive()
    check(frame is Frame.Text) { "expected a text frame, got $frame" }
    return Envelope.decode(frame.readText())
}
