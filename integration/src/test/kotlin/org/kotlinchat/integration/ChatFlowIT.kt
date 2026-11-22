package org.kotlinchat.integration

import io.ktor.server.engine.EngineSSLConnectorBuilder
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.PinStore
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.media.WebRtcDesktopEngine
import org.kotlinchat.server.auth.PasswordVerifier
import org.kotlinchat.server.config.RoomConfig
import org.kotlinchat.server.config.ServerConfig
import org.kotlinchat.server.config.TlsConfig
import org.kotlinchat.server.core.ChatServer
import org.kotlinchat.server.net.Certificates
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real stack: Netty on a loopback port and the production client, no test engine.
 */
class ChatFlowIT {

    private val password = "correct horse battery staple"
    private val verifier = PasswordVerifier.create(password, memoryKib = 16, iterations = 1)

    @Test
    fun `two clients authenticate, join the lobby and exchange chat`() = runBlocking {
        startServer(verifier).use { running ->
            val alice = ChatClient(this, InMemoryPins())
            val bob = ChatClient(this, InMemoryPins())
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)

                alice.awaitReady()
                bob.awaitReady()

                assertEquals("lobby", alice.snapshot.value.roomId)
                assertEquals("test", alice.snapshot.value.serverName)

                alice.awaitUntil { it.members.size == 2 }
                bob.awaitUntil { it.members.size == 2 }

                assertTrue(alice.sendChat("hello bob"))
                bob.awaitUntil { snapshot -> snapshot.messages.any { it.text == "hello bob" } }
                alice.awaitUntil { snapshot -> snapshot.messages.any { it.text == "hello bob" } }

                val received = bob.snapshot.value.messages.first { it.text == "hello bob" }
                assertEquals("alice", received.name)
                assertEquals("alice", received.from.let { id -> bob.snapshot.value.members.first { it.sessionId == id }.name })
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `joining a room moves presence for everyone`() = runBlocking {
        startServer(verifier).use { running ->
            val alice = ChatClient(this, InMemoryPins())
            val bob = ChatClient(this, InMemoryPins())
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()
                bob.awaitUntil { it.members.size == 2 }

                assertTrue(alice.joinRoom("dev"))
                alice.awaitUntil { it.roomId == "dev" }
                bob.awaitUntil { it.members.size == 1 }

                assertEquals("dev", alice.snapshot.value.roomId)
                assertEquals(1, bob.snapshot.value.members.size)
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `wrong password fails terminally`() = runBlocking {
        startServer(verifier).use { running ->
            val client = ChatClient(this, InMemoryPins())
            try {
                client.connect(endpoint(running.port), "mallory", "not the password")
                withTimeout(15_000) {
                    client.snapshot.first { it.connection is ConnectionState.Failed }
                }
                val state = client.snapshot.value.connection as ConnectionState.Failed
                assertTrue(state.reason.contains("password", ignoreCase = true), "reason was: ${state.reason}")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `reconnects after the server restarts on the same port`() = runBlocking {
        val port = freePort()
        val first = startServer(verifier, port = port)
        val client = ChatClient(this, InMemoryPins())

        try {
            client.connect(endpoint(port), "alice", password)
            client.awaitReady()

            first.close()
            withTimeout(15_000) {
                client.snapshot.first { it.connection is ConnectionState.Reconnecting }
            }

            startServer(verifier, port = port).use { second ->
                withTimeout(20_000) {
                    client.snapshot.first { it.connection is ConnectionState.Ready }
                }
                assertEquals("lobby", client.snapshot.value.roomId)
                assertEquals(1, second.chat.state.sessionCount())
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `two clients negotiate voice through the server`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        startServer(verifier).use { running ->
            val alice = ChatClient(this, InMemoryPins(), engine)
            val bob = ChatClient(this, InMemoryPins(), engine)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()

                alice.awaitUntil { it.voice.active }
                bob.awaitUntil { it.voice.active }

                alice.setMicrophoneMuted(false)
                bob.setMicrophoneMuted(false)

                withTimeout(30_000) { alice.snapshot.first { it.voice.connectedPeers == 1 } }
                withTimeout(30_000) { bob.snapshot.first { it.voice.connectedPeers == 1 } }

                withTimeout(30_000) {
                    alice.snapshot.first { snapshot ->
                        snapshot.voice.links.values.any { it.bytesReceived > 0 }
                    }
                }
                withTimeout(30_000) {
                    bob.snapshot.first { snapshot ->
                        snapshot.voice.links.values.any { it.bytesReceived > 0 }
                    }
                }

                val alicePeer = alice.snapshot.value.voice.links.values.single()
                val bobPeer = bob.snapshot.value.voice.links.values.single()

                assertEquals(PeerLinkState.CONNECTED, alicePeer.state)
                assertEquals(PeerLinkState.CONNECTED, bobPeer.state)
                assertTrue(alicePeer.bytesReceived > 0, "alice received no audio")
                assertTrue(bobPeer.bytesReceived > 0, "bob received no audio")
                assertFalse(alicePeer.relayed || bobPeer.relayed, "loopback should not need a relay")
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `tls is pinned on first use and a changed pin is refused`() = runBlocking {
        startServer(verifier, tls = true).use { running ->
            val pins = InMemoryPins()
            val client = ChatClient(this, pins)
            try {
                client.connect(endpoint(running.port, tls = true), "alice", password)
                client.awaitReady()

                val pinned = pins.get("127.0.0.1:${running.port}")
                assertNotNull(pinned)
                assertEquals(running.fingerprint, pinned)
                assertEquals(pinned, client.snapshot.value.serverFingerprint)
            } finally {
                client.close()
            }

            val wrongPins = InMemoryPins().apply {
                put("127.0.0.1:${running.port}", "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF")
            }
            val impostor = ChatClient(this, wrongPins)
            try {
                impostor.connect(endpoint(running.port, tls = true), "bob", password)
                withTimeout(15_000) {
                    impostor.snapshot.first { it.connection is ConnectionState.Failed }
                }
                val failure = impostor.snapshot.value.connection as ConnectionState.Failed
                assertTrue(failure.reason.contains("certificate changed"), "reason was: ${failure.reason}")
            } finally {
                impostor.close()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun endpoint(port: Int, tls: Boolean = false) =
        ServerEndpoint(host = "127.0.0.1", port = port, tls = tls)

    private suspend fun ChatClient.awaitReady() {
        withTimeout(20_000) { snapshot.first { it.connection is ConnectionState.Ready } }
    }

    private suspend fun ChatClient.awaitUntil(predicate: (ChatSnapshot) -> Boolean) {
        withTimeout(20_000) { snapshot.first(predicate) }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun startServer(
        verifier: PasswordVerifier,
        tls: Boolean = false,
        port: Int = 0,
    ): RunningServer {
        val config = ServerConfig(
            serverName = "test",
            tls = TlsConfig(enabled = tls),
            maxUsersPerRoom = 4,
            rooms = listOf(
                RoomConfig(id = "lobby", name = "Lobby"),
                RoomConfig(id = "dev", name = "Dev"),
            ),
        )
        val chat = ChatServer(config, verifier)

        var fingerprint: String? = null
        val server: EmbeddedServer<*, *> = if (tls) {
            val storePassword = "test".toCharArray()
            val keyStore = Certificates.createKeyStore(
                alias = "test",
                password = storePassword,
                commonName = "localhost",
                subjectAltNames = listOf("localhost", "127.0.0.1"),
            )
            fingerprint = Certificates.fingerprint(keyStore, "test")
            val connector = EngineSSLConnectorBuilder(
                keyStore,
                "test",
                { storePassword },
                { storePassword },
            ).apply {
                host = "127.0.0.1"
                this.port = port
            }
            embeddedServer(
                factory = Netty,
                environment = applicationEnvironment(),
                configure = { connectors.add(connector) },
                module = { chat.install(this) },
            ).start(wait = false)
        } else {
            embeddedServer(Netty, port = port, host = "127.0.0.1", module = { chat.install(this) })
                .start(wait = false)
        }

        val resolvedPort = server.engine.resolvedConnectors().first().port
        return RunningServer(server, chat, resolvedPort, fingerprint)
    }
}

private class RunningServer(
    val server: EmbeddedServer<*, *>,
    val chat: ChatServer,
    val port: Int,
    val fingerprint: String?,
) : AutoCloseable {
    override fun close() {
        server.stop(300, 1_000)
    }
}

private class InMemoryPins : PinStore {
    private val values = mutableMapOf<String, String>()

    override fun get(hostKey: String): String? = values[hostKey]

    override fun put(hostKey: String, fingerprint: String) {
        values[hostKey] = fingerprint
    }
}
