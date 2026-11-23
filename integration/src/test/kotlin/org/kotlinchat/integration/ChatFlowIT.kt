package org.kotlinchat.integration

import io.ktor.server.engine.EngineSSLConnectorBuilder
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.PinStore
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.client.ServerKeyTrust
import org.kotlinchat.client.file.TransferDirection
import org.kotlinchat.client.file.TransferProgress
import org.kotlinchat.client.file.TransferState
import org.kotlinchat.client.history.ChatHistoryStore
import org.kotlinchat.client.rtc.RtcEngine
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.media.WebRtcDesktopEngine
import org.kotlinchat.protocol.ChatMessage
import java.nio.file.Files
import java.nio.file.Path
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real stack: Netty on a loopback port and the production client, no test engine.
 */
class ChatFlowIT {

    private val password = "correct horse battery staple"
    private val verifier = PasswordVerifier.create(password, memoryKib = 16, iterations = 1)

    private fun newClient(
        scope: CoroutineScope,
        pins: PinStore = InMemoryPins(),
        engine: RtcEngine? = null,
        history: ChatHistoryStore = InMemoryHistoryStore(),
    ) = ChatClient(scope, pins, engine, history)

    @Test
    fun `two clients authenticate, join the lobby and exchange chat`() = runBlocking {
        startServer(verifier).use { running ->
            val alice = newClient(this)
            val bob = newClient(this)
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
    fun `a new client restores locally stored chat history`() = runBlocking {
        startServer(verifier).use { running ->
            val endpoint = endpoint(running.port)
            val history = InMemoryHistoryStore()
            val alice = newClient(this, history = history)
            try {
                alice.connect(endpoint, "alice", password)
                alice.awaitReady()
                assertTrue(alice.sendChat("persisted locally"))
                alice.awaitUntil { snapshot -> snapshot.messages.any { it.text == "persisted locally" } }
                withTimeout(5_000) {
                    while (history.load(endpoint.key, "lobby", 10).none { it.text == "persisted locally" }) {
                        delay(20)
                    }
                }
            } finally {
                alice.close()
            }

            val bob = newClient(this, history = history)
            try {
                bob.connect(endpoint, "bob", password)
                bob.awaitReady()
                bob.awaitUntil { snapshot -> snapshot.messages.any { it.text == "persisted locally" } }
                val restored = bob.snapshot.value.messages.first { it.text == "persisted locally" }
                assertEquals("alice", restored.name)
            } finally {
                bob.close()
            }
        }
    }

    @Test
    fun `a member creates a room that everyone sees`() = runBlocking {
        startServer(verifier).use { running ->
            val alice = newClient(this)
            val bob = newClient(this)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()
                bob.awaitUntil { it.members.size == 2 }

                assertTrue(alice.createRoom("Gaming", "after hours", voice = false))
                alice.awaitUntil { it.roomId == "gaming" }
                bob.awaitUntil { snapshot -> snapshot.rooms.any { it.name == "Gaming" } }

                val created = bob.snapshot.value.rooms.single { it.name == "Gaming" }
                assertEquals("after hours", created.topic)
                assertEquals(0, created.cap, "text rooms are not capped")

                assertTrue(bob.joinRoom(created.id))
                bob.awaitUntil { it.roomId == "gaming" }
                alice.awaitUntil { it.members.size == 2 }

                // A taken name is refused and leaves the list alone.
                val roomsBefore = bob.snapshot.value.rooms.size
                assertFalse(alice.createRoom("Gaming"))
                assertEquals(roomsBefore, bob.snapshot.value.rooms.size)
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `joining a room moves presence for everyone`() = runBlocking {
        startServer(verifier).use { running ->
            val alice = newClient(this)
            val bob = newClient(this)
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
            val client = newClient(this)
            try {
                client.connect(endpoint(running.port), "mallory", "not the password")
                withTimeout(15_000) {
                    client.snapshot.first { it.connection is ConnectionState.Failed }
                }
                val state = client.snapshot.value.connection as ConnectionState.Failed
                assertTrue(state.reason.contains("password", ignoreCase = true), "reason was: ${state.reason}")
                assertNotNull(state.failure.hint, "a failure should say what to try")
                Unit
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `a closed port explains itself instead of retrying silently`() = runBlocking {
        val port = freePort()
        val client = newClient(this)
        try {
            client.connect(endpoint(port), "alice", password)
            val state = withTimeout(20_000) {
                client.snapshot.first {
                    val connection = it.connection
                    connection is ConnectionState.Reconnecting && connection.cause != null
                }
            }.connection as ConnectionState.Reconnecting

            assertTrue(
                state.cause!!.contains("listening", ignoreCase = true),
                "cause was: ${state.cause}",
            )
            assertNotNull(state.hint, "a retry should say what to try")
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun `reconnects after the server restarts on the same port`() = runBlocking {
        val port = freePort()
        val first = startServer(verifier, port = port)
        val client = newClient(this)

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
            val alice = newClient(this, engine = engine)
            val bob = newClient(this, engine = engine)
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
    fun `four clients form a full mesh and churn removes peers`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        startServer(verifier).use { running ->
            val clients = (1..4).map { index -> "user$index" to newClient(this, engine = engine) }
            try {
                clients.forEach { (name, client) -> client.connect(endpoint(running.port), name, password) }
                clients.forEach { (_, client) -> client.awaitReady() }
                clients.forEach { (_, client) -> client.awaitUntil { it.members.size == 4 } }
                clients.forEach { (_, client) -> client.setMicrophoneMuted(false) }

                clients.forEach { (_, client) ->
                    withTimeout(60_000) { client.snapshot.first { it.voice.connectedPeers == 3 } }
                }
                clients.forEach { (_, client) ->
                    val links = client.snapshot.value.voice.links
                    assertEquals(3, links.size, "each client should have three peers")
                    assertTrue(links.values.all { it.state == PeerLinkState.CONNECTED })
                    withTimeout(30_000) {
                        client.snapshot.first { snapshot ->
                            snapshot.voice.links.values.all { it.bytesReceived > 0 }
                        }
                    }
                }

                // Churn: one member leaves and every survivor drops exactly that link.
                val (leaverName, leaver) = clients.first()
                val survivors = clients.drop(1)
                leaver.close()

                survivors.forEach { (_, client) ->
                    withTimeout(20_000) {
                        client.snapshot.first { snapshot ->
                            snapshot.voice.links.size == 2 &&
                                snapshot.members.none { it.name == leaverName }
                        }
                    }
                }
                survivors.forEach { (_, client) ->
                    val links = client.snapshot.value.voice.links
                    assertEquals(2, links.size)
                    assertTrue(links.values.all { it.state == PeerLinkState.CONNECTED })
                }
            } finally {
                clients.forEach { (_, client) -> client.close() }
            }
        }
    }

    @Test
    fun `screen share reaches the other client and stops`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        val sources = engine.screenSources()
        assumeTrue(sources.isNotEmpty(), "no screen capture sources available")
        val screen = sources.firstOrNull { !it.isWindow } ?: sources.first()

        startServer(verifier).use { running ->
            val alice = newClient(this, engine = engine)
            val bob = newClient(this, engine = engine)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()
                alice.awaitUntil { it.members.size == 2 }
                withTimeout(30_000) { bob.snapshot.first { it.voice.connectedPeers == 1 } }

                alice.startScreenShare(screen)
                alice.awaitUntil { it.screen.sharing }

                bob.awaitUntil { it.screen.remote != null }
                val frame = bob.snapshot.value.screen.remote
                assertNotNull(frame)
                assertTrue(frame.width > 0 && frame.height > 0)
                assertTrue(frame.rgba.size == frame.width * frame.height * 4)
                assertNotNull(bob.snapshot.value.screen.remoteFrom)

                bob.awaitUntil { snapshot -> snapshot.members.any { it.sharing } }

                alice.stopScreenShare()
                alice.awaitUntil { !it.screen.sharing }
                bob.awaitUntil { snapshot -> snapshot.members.none { it.sharing } }
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `file transfer lands on the receiver with a matching hash`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        val sourceDir = Files.createTempDirectory("kc-src")
        val downloadDir = Files.createTempDirectory("kc-dst")
        val payload = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
        val sourceFile = sourceDir.resolve("payload.bin")
        Files.write(sourceFile, payload)

        try {
            startServer(verifier).use { running ->
                val alice = newClient(this, engine = engine)
                val bob = newClient(this, engine = engine)
                bob.setDownloadDirectory(downloadDir.toString())
                try {
                    alice.connect(endpoint(running.port), "alice", password)
                    bob.connect(endpoint(running.port), "bob", password)
                    alice.awaitReady()
                    bob.awaitReady()
                    alice.awaitUntil { it.members.size == 2 }
                    withTimeout(30_000) { alice.snapshot.first { it.voice.connectedPeers == 1 } }
                    withTimeout(30_000) { bob.snapshot.first { it.voice.connectedPeers == 1 } }

                    alice.offerFile(sourceFile.toString(), "a caption for the file")

                    val offer = bob.awaitTransfer { transfer ->
                        transfer.direction == TransferDirection.INCOMING &&
                            transfer.state == TransferState.OFFERED &&
                            transfer.caption == "a caption for the file"
                    }
                    assertEquals("a caption for the file", offer.caption)
                    bob.acceptTransfer(offer.id, offer.peerId)

                    val received = bob.awaitTransfer { transfer ->
                        transfer.key == offer.key && transfer.state == TransferState.COMPLETED
                    }
                    assertEquals(payload.size.toLong(), received.bytes)
                    assertEquals("a caption for the file", received.caption)
                    val savedPath = assertNotNull(received.savedPath)
                    assertTrue(
                        Files.readAllBytes(Path.of(savedPath)).contentEquals(payload),
                        "received file must match the source byte for byte",
                    )

                    val sent = alice.awaitTransfer { transfer ->
                        transfer.direction == TransferDirection.OUTGOING &&
                            transfer.state == TransferState.COMPLETED
                    }
                    assertEquals(payload.size.toLong(), sent.bytes)
                    assertEquals("a caption for the file", sent.caption)
                } finally {
                    alice.close()
                    bob.close()
                }
            }
        } finally {
            sourceDir.toFile().deleteRecursively()
            downloadDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tls is pinned on first use and a changed pin is refused`() = runBlocking {
        startServer(verifier, tls = true).use { running ->
            val pins = InMemoryPins()
            val client = newClient(this, pins = pins)
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
            val impostor = newClient(this, pins = wrongPins)
            try {
                impostor.connect(endpoint(running.port, tls = true), "bob", password)
                withTimeout(15_000) {
                    impostor.snapshot.first { it.connection is ConnectionState.Failed }
                }
                val failure = impostor.snapshot.value.connection as ConnectionState.Failed
                assertTrue(failure.reason.contains("key", ignoreCase = true), "reason was: ${failure.reason}")
                assertTrue(
                    failure.failure.hint.orEmpty().contains("Forget server key"),
                    "hint was: ${failure.failure.hint}",
                )
                assertTrue(
                    failure.failure.detail.orEmpty().contains("certificate changed"),
                    "detail was: ${failure.failure.detail}",
                )
            } finally {
                impostor.close()
            }
        }
    }

    @Test
    fun `a server key is shown before it is pinned by the user`() = runBlocking {
        startServer(verifier, tls = true).use { running ->
            val pins = InMemoryPins()
            val client = newClient(this, pins = pins)
            val endpoint = endpoint(running.port, tls = true)
            try {
                val presented = assertNotNull(running.fingerprint)
                val first = client.checkServerKey(endpoint)
                assertTrue(first is ServerKeyTrust.FirstSight, "first contact should ask, was: $first")
                assertEquals(presented, (first as ServerKeyTrust.FirstSight).fingerprint)
                assertNull(pins.get(endpoint.key), "nothing may be pinned before the user agrees")

                client.trustServerKey(endpoint, first.approvedFingerprint)
                assertEquals(presented, pins.get(endpoint.key))
                assertEquals(
                    ServerKeyTrust.Pinned(presented),
                    client.checkServerKey(endpoint),
                )

                // Approving a key never replaces one that is already pinned.
                client.trustServerKey(endpoint, "00:11:22:33:44:55:66:77")
                assertEquals(running.fingerprint, pins.get(endpoint.key), "existing pin must win")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `a changed server key is reported with both fingerprints`() = runBlocking {
        startServer(verifier, tls = true).use { running ->
            val pins = InMemoryPins()
            val endpoint = endpoint(running.port, tls = true)
            val old = "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
            pins.put(endpoint.key, old)

            val client = newClient(this, pins = pins)
            try {
                val presented = assertNotNull(running.fingerprint)
                val check = client.checkServerKey(endpoint)
                assertEquals(ServerKeyTrust.Changed(pinned = old, presented = presented), check)
                assertNull(client.snapshot.value.room, "checking a key must not connect")
            } finally {
                client.close()
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

    private suspend fun ChatClient.awaitTransfer(
        predicate: (TransferProgress) -> Boolean,
    ): TransferProgress = withTimeout(60_000) {
        transfers.first { list -> list.any(predicate) }.first(predicate)
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
            // Keep created rooms out of the repository's config directory.
            clientRoomsFile = Files.createTempDirectory("kotlin-chat-rooms")
                .resolve("rooms.json")
                .toString(),
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

    override fun remove(hostKey: String) {
        values.remove(hostKey)
    }
}

private class InMemoryHistoryStore : ChatHistoryStore {
    private val data = mutableMapOf<Pair<String, String>, MutableList<ChatMessage>>()

    @Synchronized
    override fun load(serverKey: String, roomId: String, limit: Int): List<ChatMessage> =
        (data[serverKey to roomId] ?: emptyList())
            .distinctBy { it.messageId }
            .sortedBy { it.ts }
            .takeLast(limit)

    @Synchronized
    override fun append(serverKey: String, roomId: String, message: ChatMessage) {
        data.getOrPut(serverKey to roomId) { mutableListOf() }.add(message)
    }

    @Synchronized
    override fun clear(serverKey: String, roomId: String?) {
        if (roomId == null) {
            data.keys.filter { it.first == serverKey }.forEach { data.remove(it) }
        } else {
            data.remove(serverKey to roomId)
        }
    }
}
