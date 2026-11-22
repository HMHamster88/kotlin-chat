package org.kotlinchat.integration

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.Hello
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.decodePayload
import org.kotlinchat.server.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the real stack over TCP: Netty server on an ephemeral port,
 * production [ChatClient] on the other end. No test engine, no fakes.
 */
class ControlChannelIT {

    @Test
    fun `client connects and receives the echoed hello`() = runBlocking {
        val server = embeddedServer(Netty, port = 0) { module() }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = ChatClient()

        try {
            val received = CompletableDeferred<Envelope>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                client.incoming.collect { envelope ->
                    if (envelope.type == MessageType.ECHO) received.complete(envelope)
                }
            }

            val result = client.connect("127.0.0.1", port, "integration")
            assertTrue(result.isSuccess, "connect failed: ${result.exceptionOrNull()?.message}")

            val echo = withTimeout(10_000) { received.await() }
            assertEquals(MessageType.ECHO, echo.type)
            assertEquals("integration", echo.decodePayload<Hello>().name)
            assertEquals(ConnectionState.Connected, client.state.value)

            collector.cancel()
        } finally {
            client.close()
            server.stop(500, 2_000)
        }
    }
}
