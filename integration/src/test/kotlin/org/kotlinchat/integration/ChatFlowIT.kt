package org.kotlinchat.integration

import io.ktor.server.engine.EngineSSLConnectorBuilder
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.event.KeyEvent
import kotlin.math.abs
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
import org.kotlinchat.client.rtc.AudioDeviceInfo
import org.kotlinchat.client.rtc.AudioDeviceSelection
import org.kotlinchat.client.rtc.OutgoingSignal
import org.kotlinchat.client.rtc.PeerDataChannel
import org.kotlinchat.client.rtc.PeerLinkInfo
import org.kotlinchat.client.rtc.RtcOptions
import org.kotlinchat.client.rtc.RtcSession
import org.kotlinchat.client.rtc.VideoFrameData
import org.kotlinchat.client.control.RemoteInputSink
import org.kotlinchat.client.control.RemoteMouseButton
import org.kotlinchat.client.control.ScreenControlPhase
import org.kotlinchat.client.control.ScreenControlRole
import org.kotlinchat.client.rtc.ScreenShareSource
import org.kotlinchat.media.WebRtcDesktopEngine
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.SignalBody
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
        inputSink: RemoteInputSink? = null,
    ) = ChatClient(scope, pins, engine, history, inputSink)

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
                alice.awaitUntil { it.roomId == "lobby" }
                bob.awaitUntil { it.roomId == "lobby" }

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
                alice.awaitUntil { it.roomId == "lobby" }
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
                alice.awaitUntil { snapshot -> snapshot.rooms.any { it.name == "Gaming" } }
                val created = alice.snapshot.value.rooms.single { it.name == "Gaming" }
                alice.awaitUntil { it.roomId == created.id }
                bob.awaitUntil { snapshot -> snapshot.rooms.any { it.name == "Gaming" } }

                val createdForBob = bob.snapshot.value.rooms.single { it.name == "Gaming" }
                assertEquals("after hours", createdForBob.topic)
                assertEquals(0, createdForBob.cap, "text rooms are not capped")

                assertTrue(bob.joinRoom(createdForBob.id))
                bob.awaitUntil { it.roomId == createdForBob.id }
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
    fun `an empty created room is removed for everyone`() = runBlocking {
        startServer(verifier).use { running ->
            val alice = newClient(this)
            val bob = newClient(this)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitUntil { it.roomId == "lobby" }
                bob.awaitUntil { it.roomId == "lobby" }

                assertTrue(alice.createRoom("Temporary"))
                alice.awaitUntil { snapshot -> snapshot.rooms.any { it.name == "Temporary" } }
                val room = alice.snapshot.value.rooms.single { it.name == "Temporary" }
                bob.awaitUntil { snapshot -> snapshot.rooms.any { it.id == room.id } }

                assertTrue(alice.leaveRoom())
                alice.awaitUntil { it.room == null }
                alice.awaitUntil { room.id in it.deletableRoomIds }
                bob.awaitUntil { room.id in it.deletableRoomIds }

                assertTrue(alice.deleteRoom(room.id))
                alice.awaitUntil { snapshot -> snapshot.rooms.none { it.id == room.id } }
                bob.awaitUntil { snapshot -> snapshot.rooms.none { it.id == room.id } }
                assertTrue(alice.deleteRoom("missing-room"))
                alice.awaitUntil { it.lastError?.contains("no such room") == true }
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
    fun `speaker mute stays local and the call flag changes with voice`() = runBlocking {
        startServer(verifier).use { running ->
            val engine = RecordingRtcEngine()
            val alice = newClient(this, engine = engine)
            val bob = newClient(this)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()
                bob.awaitUntil { it.members.size == 2 }

                // Entering the room is not a call.
                bob.awaitUntil { snapshot ->
                    snapshot.members.first { it.name == "alice" }.let { it.selfMute && it.selfDeaf }
                }

                assertTrue(alice.joinVoice())
                alice.awaitUntil { it.voice.joined }
                bob.awaitUntil { snapshot ->
                    snapshot.members.first { it.name == "alice" }.let { !it.selfMute && !it.selfDeaf }
                }

                // Speaker mute is local and must not make the peer look like it left the call.
                alice.setSpeakerMuted(true)
                assertTrue(alice.snapshot.value.voice.speakerMuted)
                assertFalse(alice.snapshot.value.voice.muted)
                delay(300)
                val afterSpeakerMute = bob.snapshot.value.members.first { it.name == "alice" }
                assertFalse(afterSpeakerMute.selfDeaf, "speaker mute is not call membership")
                assertFalse(afterSpeakerMute.selfMute)
                alice.setSpeakerMuted(false)

                // A muted microphone still shows the call, with the microphone crossed out.
                alice.setMicrophoneMuted(true)
                assertTrue(alice.snapshot.value.voice.muted)
                bob.awaitUntil { snapshot ->
                    snapshot.members.first { it.name == "alice" }.let { it.selfMute && !it.selfDeaf }
                }

                alice.leaveVoice()
                bob.awaitUntil { snapshot ->
                    snapshot.members.first { it.name == "alice" }.selfDeaf
                }
                Unit
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `leaving a room keeps the server session open`() = runBlocking {
        startServer(verifier).use { running ->
            val alice = newClient(this)
            val bob = newClient(this)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()
                bob.awaitUntil { it.members.size == 2 }
                assertNotNull(alice.snapshot.value.room)

                assertTrue(alice.leaveRoom())
                alice.awaitUntil { it.room == null }
                assertTrue(alice.snapshot.value.connection is ConnectionState.Ready)
                assertNull(alice.snapshot.value.roomId)
                bob.awaitUntil { it.members.size == 1 }

                assertTrue(alice.joinRoom("dev"))
                alice.awaitUntil { it.roomId == "dev" }
                assertEquals("alice", alice.snapshot.value.members.single().name)
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `voice reconnects after both clients switch rooms`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        startServer(verifier).use { running ->
            val alice = newClient(this, engine = engine)
            val bob = newClient(this, engine = engine)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitUntil { it.roomId == "lobby" }
                bob.awaitUntil { it.roomId == "lobby" }
                alice.setMicrophoneMuted(false)
                bob.setMicrophoneMuted(false)
                withTimeout(30_000) { alice.snapshot.first { it.voice.connectedPeers == 1 } }
                withTimeout(30_000) { bob.snapshot.first { it.voice.connectedPeers == 1 } }

                assertTrue(alice.joinRoom("dev"))
                alice.awaitUntil { it.roomId == "dev" }
                bob.awaitUntil { it.members.size == 1 }
                assertTrue(bob.joinRoom("dev"))
                bob.awaitUntil { it.roomId == "dev" }

                withTimeout(30_000) { alice.snapshot.first { it.voice.connectedPeers == 1 } }
                withTimeout(30_000) { bob.snapshot.first { it.voice.connectedPeers == 1 } }
                withTimeout(30_000) {
                    alice.snapshot.first { it.voice.links.values.any { link -> link.bytesReceived > 0 } }
                }
                withTimeout(30_000) {
                    bob.snapshot.first { it.voice.links.values.any { link -> link.bytesReceived > 0 } }
                }
                Unit
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `a remembered room is joined on the next connection`() = runBlocking {
        startServer(verifier).use { running ->
            val client = newClient(this)
            try {
                client.setInitialRoom("dev")
                client.connect(endpoint(running.port), "alice", password)
                client.awaitReady()
                client.awaitUntil { it.roomId == "dev" }
                assertEquals("dev", client.snapshot.value.roomId)
            } finally {
                client.close()
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
                client.awaitUntil { it.roomId == "lobby" }
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
    fun `remote control grants input and revokes it`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")
        val sources = engine.screenSources()
        assumeTrue(sources.isNotEmpty(), "no screen capture sources available")
        val screen = sources.firstOrNull { !it.isWindow } ?: sources.first()
        val sink = RecordingInputSink()

        startServer(verifier).use { running ->
            val alice = newClient(this, engine = engine, inputSink = sink)
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
                bob.awaitUntil { snapshot -> snapshot.members.any { it.name == "alice" && it.sharing } }

                val aliceId = bob.snapshot.value.members.first { it.name == "alice" }.sessionId
                val bobId = alice.snapshot.value.members.first { it.name == "bob" }.sessionId
                withTimeout(10_000) {
                    while (bob.snapshot.value.control.phase != ScreenControlPhase.REQUESTING) {
                        bob.requestScreenControl(aliceId)
                        delay(100)
                    }
                }
                alice.awaitUntil {
                    it.control.phase == ScreenControlPhase.REQUESTED && it.control.peerId == bobId
                }
                alice.grantScreenControl()
                bob.awaitUntil {
                    it.control.phase == ScreenControlPhase.ACTIVE &&
                        it.control.role == ScreenControlRole.VIEWER
                }
                alice.awaitUntil {
                    it.control.phase == ScreenControlPhase.ACTIVE &&
                        it.control.role == ScreenControlRole.HOST
                }
                val expires = alice.snapshot.value.control.expiresAtMillis
                assertNotNull(expires)
                assertTrue(expires - System.currentTimeMillis() > 20 * 60_000L)

                bob.sendScreenControlPointerMove(0.25f, 0.75f)
                bob.sendScreenControlButton(RemoteMouseButton.LEFT, true)
                bob.sendScreenControlButton(RemoteMouseButton.LEFT, false)
                bob.sendScreenControlKey(KeyEvent.VK_A, true)
                bob.sendScreenControlKey(KeyEvent.VK_A, false)

                withTimeout(10_000) {
                    val move = sink.flow.first { it is SinkEvent.Move } as SinkEvent.Move
                    assertTrue(abs(move.x - 0.25f) < 0.02f, "move x=${move.x}")
                    assertTrue(abs(move.y - 0.75f) < 0.02f, "move y=${move.y}")
                    val down = sink.flow.first { it is SinkEvent.Button && it.down } as SinkEvent.Button
                    assertEquals(RemoteMouseButton.LEFT, down.button)
                    val up = sink.flow.first { it is SinkEvent.Button && !it.down } as SinkEvent.Button
                    assertEquals(RemoteMouseButton.LEFT, up.button)
                    val keyDown = sink.flow.first { it is SinkEvent.Key && it.down } as SinkEvent.Key
                    assertEquals(KeyEvent.VK_A, keyDown.keyCode)
                    val keyUp = sink.flow.first { it is SinkEvent.Key && !it.down } as SinkEvent.Key
                    assertEquals(KeyEvent.VK_A, keyUp.keyCode)
                }

                bob.releaseScreenControl()
                alice.awaitUntil { it.control.phase == ScreenControlPhase.IDLE }
                withTimeout(5_000) { sink.flow.first { it is SinkEvent.Released } }
                Unit
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `a missing remembered room falls back to the lobby`() = runBlocking {
        startServer(verifier).use { running ->
            val alice = newClient(this)
            try {
                alice.setInitialRoom("no-such-room")
                alice.connect(endpoint(running.port), "alice", password)
                alice.awaitReady()
                alice.awaitUntil { it.roomId == "lobby" }
                assertTrue(alice.snapshot.value.rooms.isNotEmpty())
                Unit
            } finally {
                alice.close()
            }
        }
    }

    @Test
    fun `voice starts off and joins and leaves on request`() = runBlocking {
        startServer(verifier).use { running ->
            val engine = WebRtcDesktopEngine()
            val alice = newClient(this, engine = engine)
            val bob = newClient(this, engine = engine)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()
                alice.awaitUntil { it.roomId != null }
                bob.awaitUntil { it.roomId != null }

                // The media mesh is up for files and screen sharing, but the call is not started.
                alice.awaitUntil { it.voice.active }
                assertFalse(alice.snapshot.value.voice.joined)
                assertTrue(alice.snapshot.value.voice.muted)
                assertTrue(alice.snapshot.value.voice.speakerMuted)
                withTimeout(30_000) { bob.snapshot.first { it.voice.connectedPeers == 1 } }
                bob.awaitUntil {
                    it.members.first { member -> member.name == "alice" }.let { member ->
                        member.selfMute && member.selfDeaf
                    }
                }

                assertTrue(alice.joinVoice())
                alice.awaitUntil { it.voice.joined && !it.voice.muted && !it.voice.speakerMuted }
                alice.awaitUntil { it.voice.active }
                bob.awaitUntil {
                    it.members.first { member -> member.name == "alice" }.let { member ->
                        !member.selfMute && !member.selfDeaf
                    }
                }

                // A microphone mute inside the call is not the same as not being in it.
                alice.setMicrophoneMuted(true)
                alice.awaitUntil { it.voice.muted }
                bob.awaitUntil {
                    it.members.first { member -> member.name == "alice" }.let { member ->
                        member.selfMute && !member.selfDeaf
                    }
                }
                alice.setMicrophoneMuted(false)

                alice.leaveVoice()
                alice.awaitUntil { !it.voice.joined && it.voice.muted }
                assertTrue(alice.snapshot.value.voice.active, "the room mesh must survive a hang-up")
                bob.awaitUntil {
                    it.members.first { member -> member.name == "alice" }.let { member ->
                        member.selfMute && member.selfDeaf
                    }
                }

                // Without a call, chat and the room still work.
                assertTrue(alice.sendChat("text without voice"))
                bob.awaitUntil { snapshot -> snapshot.messages.any { it.text == "text without voice" } }
                Unit
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `switching rooms ends the call`() = runBlocking {
        startServer(verifier).use { running ->
            val engine = WebRtcDesktopEngine()
            val alice = newClient(this, engine = engine)
            val bob = newClient(this, engine = engine)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()
                alice.awaitUntil { it.roomId != null }
                bob.awaitUntil { it.roomId != null }

                assertTrue(alice.joinVoice())
                alice.awaitUntil { it.voice.joined }
                alice.awaitUntil { it.voice.active }

                assertTrue(alice.joinRoom("dev"))
                alice.awaitUntil { it.roomId == "dev" }
                alice.awaitUntil { !it.voice.joined && it.voice.muted }
                assertTrue(alice.snapshot.value.voice.active, "the new room's mesh should be up")
                Unit
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `a platform that rebuilds its session toggles audio with the call`() = runBlocking {
        startServer(verifier).use { running ->
            val recorder = RecordingRtcEngine()
            val alice = newClient(this, engine = recorder)
            val bob = newClient(this)
            try {
                alice.connect(endpoint(running.port), "alice", password)
                bob.connect(endpoint(running.port), "bob", password)
                alice.awaitReady()
                bob.awaitReady()
                alice.awaitUntil { it.roomId != null }
                bob.awaitUntil { it.roomId != null }

                // Entering a room builds the data-only mesh, not a call.
                waitFor { recorder.starts.size == 1 }
                assertFalse(recorder.starts.single().captureAudio)
                assertFalse(recorder.sessions.single().closed)

                assertTrue(alice.joinVoice())
                alice.awaitUntil { it.voice.joined && !it.voice.muted }
                waitFor { recorder.starts.size == 2 }
                assertTrue(recorder.starts[1].captureAudio, "joining a call must open the microphone")
                waitFor { recorder.sessions[0].closed }

                alice.leaveVoice()
                alice.awaitUntil { !it.voice.joined }
                waitFor { recorder.starts.size == 3 }
                assertFalse(recorder.starts[2].captureAudio, "leaving a call must close the microphone")
                waitFor { recorder.sessions[1].closed }
                Unit
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
        val preview = ByteArray(256) { index -> (index % 113).toByte() }
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

                    alice.offerFile(sourceFile.toString(), "a caption for the file", preview)

                    val offer = bob.awaitTransfer { transfer ->
                        transfer.direction == TransferDirection.INCOMING &&
                            transfer.state == TransferState.OFFERED &&
                            transfer.caption == "a caption for the file"
                    }
                    assertEquals("a caption for the file", offer.caption)
                    val previewed = bob.awaitTransfer { transfer ->
                        transfer.id == offer.id && transfer.preview != null
                    }
                    assertTrue(
                        previewed.preview!!.contentEquals(preview),
                        "the preview should arrive before the file is accepted",
                    )
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

sealed interface SinkEvent {
    data class Source(val title: String) : SinkEvent
    data class Move(val x: Float, val y: Float) : SinkEvent
    data class Button(val button: RemoteMouseButton, val down: Boolean) : SinkEvent
    data class Wheel(val delta: Int, val horizontal: Boolean) : SinkEvent
    data class Key(val keyCode: Int, val down: Boolean) : SinkEvent
    data object Released : SinkEvent
}

private class RecordingInputSink : RemoteInputSink {
    private val channel = Channel<SinkEvent>(Channel.UNLIMITED)
    val flow = channel.receiveAsFlow()

    override fun setSource(source: ScreenShareSource?) {
        channel.trySend(SinkEvent.Source(source?.title.orEmpty()))
    }

    override fun movePointer(x: Float, y: Float) {
        channel.trySend(SinkEvent.Move(x, y))
    }

    override fun mouseButton(button: RemoteMouseButton, down: Boolean) {
        channel.trySend(SinkEvent.Button(button, down))
    }

    override fun wheel(delta: Int, horizontal: Boolean) {
        channel.trySend(SinkEvent.Wheel(delta, horizontal))
    }

    override fun key(keyCode: Int, down: Boolean) {
        channel.trySend(SinkEvent.Key(keyCode, down))
    }

    override fun releaseAll() {
        channel.trySend(SinkEvent.Released)
    }
}

private suspend fun waitFor(predicate: () -> Boolean) {
    withTimeout(10_000) {
        while (!predicate()) delay(20)
    }
}

/** Stands in for Android's engine: every call toggle rebuilds the RTC session. */
private class RecordingRtcEngine : RtcEngine {
    override val restartsSessionForVoice: Boolean = true
    override fun available(): Boolean = true
    override fun inputDevices(): List<AudioDeviceInfo> = emptyList()
    override fun outputDevices(): List<AudioDeviceInfo> = emptyList()
    override fun screenSources(): List<ScreenShareSource> = emptyList()

    val starts = mutableListOf<RtcOptions>()
    val sessions = mutableListOf<RecordingRtcSession>()

    override suspend fun start(options: RtcOptions): RtcSession {
        starts += options
        return RecordingRtcSession().also { sessions += it }
    }
}

private class RecordingRtcSession : RtcSession {
    @Volatile
    var closed: Boolean = false

    override val localLevel = MutableStateFlow(0f)
    override val links = MutableStateFlow<Map<String, PeerLinkInfo>>(emptyMap())
    override val outgoing = MutableSharedFlow<OutgoingSignal>()
    override val sharing = MutableStateFlow(false)
    override val remoteFrames = MutableStateFlow<Map<String, VideoFrameData>>(emptyMap())
    override val dataChannels = MutableStateFlow<Map<String, PeerDataChannel>>(emptyMap())

    override fun addPeer(peerId: String, polite: Boolean) = Unit
    override fun removePeer(peerId: String) = Unit
    override fun handleSignal(from: String, signal: SignalBody) = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun setOutputVolume(peerId: String, volume: Float) = Unit
    override fun applyAudio(
        devices: AudioDeviceSelection,
        microphoneVolume: Float,
        speakerVolume: Float,
    ) = Unit

    override suspend fun startScreenShare(source: ScreenShareSource, maxWidth: Int, maxHeight: Int, fps: Int) = Unit
    override fun stopScreenShare() = Unit

    override fun close() {
        closed = true
    }
}
