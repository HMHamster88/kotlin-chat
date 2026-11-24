package org.kotlinchat.server

import kotlinx.coroutines.runBlocking
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.MemberLeft
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.crypto.Scram
import org.kotlinchat.protocol.decodePayload
import org.kotlinchat.server.auth.PasswordVerifier
import org.kotlinchat.server.auth.VerifierFile
import org.kotlinchat.server.config.RoomConfig
import org.kotlinchat.server.config.ServerConfig
import org.kotlinchat.server.config.TlsConfig
import org.kotlinchat.server.core.AuthThrottle
import org.kotlinchat.server.core.ChatResult
import org.kotlinchat.server.core.ChatState
import org.kotlinchat.server.core.CreateRoomResult
import org.kotlinchat.server.core.ClientSession
import org.kotlinchat.server.core.JoinResult
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatStateTest {

    private val config = ServerConfig(
        tls = TlsConfig(enabled = false),
        maxUsersPerRoom = 1,
        rooms = listOf(
            RoomConfig(id = "lobby", name = "Lobby"),
            RoomConfig(id = "dev", name = "Dev"),
        ),
    )

    private fun session(id: String) = ClientSession(id = id, remoteHost = "test")

    private fun ClientSession.frames(): List<Envelope> = buildList {
        while (true) {
            val text = outgoing.tryReceive().getOrNull() ?: break
            add(Envelope.decode(text))
        }
    }

    @Test
    fun `registration assigns a unique name and announces the member`() = runBlocking {
        val state = ChatState(config)
        val alice = session("s_a")
        val bob = session("s_b")

        state.register(alice, "alice", null) { _, _ -> }
        state.register(bob, "alice", null) { _, _ -> }

        assertEquals("alice", alice.name)
        assertEquals("alice (2)", bob.name)

        val announced = alice.frames().single { it.type == MessageType.MEMBER_JOINED }
        assertEquals(MessageType.MEMBER_JOINED, announced.type)
    }

    @Test
    fun `chat is broadcast to every member of the room`() = runBlocking {
        val state = ChatState(config)
        val alice = session("s_a")
        val bob = session("s_b")
        state.register(alice, "alice", null) { _, _ -> }
        state.register(bob, "bob", null) { _, _ -> }
        alice.frames()
        bob.frames()

        val result = state.postChat(alice, "  hello room  ", clientMsgId = "local-1")
        val message = assertIs<ChatResult.Ok>(result).message

        assertEquals("hello room", message.text)
        assertEquals("alice", message.name)

        val received = bob.frames().single { it.type == MessageType.CHAT_MESSAGE }
        val delivered = received.decodePayload<ChatMessage>()
        assertEquals(message.messageId, delivered.messageId)
        assertEquals("local-1", delivered.clientMsgId)

        val echoedToSender = alice.frames().any { it.type == MessageType.CHAT_MESSAGE }
        assertTrue(echoedToSender, "sender must receive its own message for reconciliation")
    }

    @Test
    fun `voice room capacity is enforced on join`() = runBlocking {
        val state = ChatState(config)
        val alice = session("s_a")
        val bob = session("s_b")
        state.register(alice, "alice", null) { _, _ -> }
        state.register(bob, "bob", null) { _, _ -> }

        assertEquals(JoinResult.Ok, state.join(alice, "dev") { })
        assertEquals(JoinResult.Full, state.join(bob, "dev") { })
    }

    @Test
    fun `joining another room moves presence`() = runBlocking {
        val state = ChatState(config)
        val alice = session("s_a")
        val bob = session("s_b")
        state.register(alice, "alice", null) { _, _ -> }
        state.register(bob, "bob", null) { _, _ -> }
        alice.frames()
        bob.frames()

        var moved: RoomState? = null
        assertEquals(JoinResult.Ok, state.join(alice, "dev") { moved = it })
        assertEquals("dev", moved?.room?.id)

        val left = bob.frames().single { it.type == MessageType.MEMBER_LEFT }
        assertEquals("s_a", left.decodePayload<MemberLeft>().sessionId)
        assertEquals(1, state.membersOf("lobby").size)
        assertEquals(1, state.membersOf("dev").size)
    }

    @Test
    fun `leaving a room keeps the session and allows joining again`() = runBlocking {
        val state = ChatState(config)
        val alice = session("s_a")
        val bob = session("s_b")
        state.register(alice, "alice", null) { _, _ -> }
        state.register(bob, "bob", null) { _, _ -> }
        alice.frames()
        bob.frames()

        var lobbyCount = -1
        state.leave(alice) { rooms ->
            lobbyCount = rooms.single { it.id == "lobby" }.memberCount
        }

        assertNull(alice.roomId)
        assertEquals(1, lobbyCount, "bob is still in the lobby")
        assertEquals(1, state.membersOf("lobby").size)
        assertIs<ChatResult.NotInRoom>(state.postChat(alice, "hello", null))
        val left = bob.frames().single { it.type == MessageType.MEMBER_LEFT }
        assertEquals("s_a", left.decodePayload<MemberLeft>().sessionId)

        var joined: RoomState? = null
        assertEquals(JoinResult.Ok, state.join(alice, "dev") { joined = it })
        assertEquals("dev", joined?.room?.id)
        assertEquals("dev", alice.roomId)
    }

    @Test
    fun `a member can create a room and lands in it`() = runBlocking {
        val state = ChatState(config)
        val alice = session("s_a")
        val bob = session("s_b")
        state.register(alice, "alice", null) { _, _ -> }
        state.register(bob, "bob", null) { _, _ -> }
        alice.frames()
        bob.frames()

        var moved: RoomState? = null
        val result = state.createRoom(alice, "  Gaming  ", "after hours", voice = false) { moved = it }

        val room = assertIs<CreateRoomResult.Ok>(result).room
        assertEquals("gaming", room.id)
        assertEquals("Gaming", room.name)
        assertEquals("after hours", room.topic)
        assertFalse(room.voice)
        assertEquals("gaming", moved?.room?.id)
        assertEquals("gaming", alice.roomId)

        assertTrue(
            alice.frames().any { it.type == MessageType.ROOM_LIST_RESULT },
            "the creator sees the new room list",
        )
        val announced = bob.frames()
        val left = announced.single { it.type == MessageType.MEMBER_LEFT }
        assertEquals("s_a", left.decodePayload<MemberLeft>().sessionId)
        assertTrue(announced.any { it.type == MessageType.ROOM_LIST_RESULT }, "everyone sees the new room")
        assertEquals(listOf("alice"), state.membersOf("gaming").map { it.name })
    }

    @Test
    fun `room names are unique and validated`() = runBlocking {
        val state = ChatState(config)
        val alice = session("s_a")
        state.register(alice, "alice", null) { _, _ -> }

        assertIs<CreateRoomResult.Ok>(state.createRoom(alice, "Gaming", "", true) { })
        assertEquals(CreateRoomResult.NameTaken, state.createRoom(alice, "gaming", "", true) { })
        assertEquals(CreateRoomResult.Invalid, state.createRoom(alice, "   ", "", true) { })
        assertEquals(3, state.roomsSummary().size, "lobby, dev and the new room")
    }

    @Test
    fun `the room limit and the switch are honoured`() = runBlocking {
        val limited = ChatState(config.copy(maxRooms = 2))
        val alice = session("s_a")
        limited.register(alice, "alice", null) { _, _ -> }
        assertEquals(CreateRoomResult.Limit, limited.createRoom(alice, "Extra", "", true) { })

        val closed = ChatState(config.copy(allowClientRooms = false))
        val bob = session("s_b")
        closed.register(bob, "bob", null) { _, _ -> }
        assertEquals(CreateRoomResult.Disabled, closed.createRoom(bob, "Extra", "", true) { })
    }

    @Test
    fun `created rooms are written to disk and reloaded`() = runBlocking {
        val directory = Files.createTempDirectory("kotlin-chat-rooms")
        val path = directory.resolve("rooms.json")

        val first = ChatState(config, path)
        val alice = session("s_a")
        first.register(alice, "alice", null) { _, _ -> }
        assertIs<CreateRoomResult.Ok>(first.createRoom(alice, "Gaming", "topic", true) { })

        val reloaded = ChatState(config, path)
        val names = reloaded.roomsSummary().map { it.name }
        assertTrue("Gaming" in names, "names were $names")
        assertEquals(1, names.count { it == "Lobby" }, "the lobby is not duplicated from the file")
    }

    @Test
    fun `chat rate limit is enforced`() = runBlocking {        val state = ChatState(config)
        val alice = session("s_a")
        state.register(alice, "alice", null) { _, _ -> }

        repeat(10) { assertIs<ChatResult.Ok>(state.postChat(alice, "burst $it", null)) }
        assertEquals(ChatResult.RateLimited, state.postChat(alice, "one too many", null))
    }

    @Test
    fun `oversized messages are rejected`() = runBlocking {
        val state = ChatState(config.copy(maxChatBytes = 8))
        val alice = session("s_a")
        state.register(alice, "alice", null) { _, _ -> }

        assertEquals(ChatResult.Invalid, state.postChat(alice, "123456789", null))
        assertEquals(ChatResult.Invalid, state.postChat(alice, "   ", null))
    }

    @Test
    fun `disconnecting removes the member from the room`() = runBlocking {
        val state = ChatState(config)
        val alice = session("s_a")
        val bob = session("s_b")
        state.register(alice, "alice", null) { _, _ -> }
        state.register(bob, "bob", null) { _, _ -> }
        bob.frames()

        state.unregister(alice)
        assertEquals(1, state.sessionCount())
        assertEquals(1, state.membersOf("lobby").size)
        assertTrue(bob.frames().any { it.type == MessageType.MEMBER_LEFT })
    }
}

class AuthThrottleTest {

    @Test
    fun `locks out after repeated failures and clears on success`() {
        val throttle = AuthThrottle(maxFailures = 2, windowMillis = 60_000, lockoutMillis = 60_000)
        assertFalse(throttle.isLocked("1.2.3.4"))

        throttle.recordFailure("1.2.3.4")
        assertFalse(throttle.isLocked("1.2.3.4"))

        throttle.recordFailure("1.2.3.4")
        assertTrue(throttle.isLocked("1.2.3.4"))

        throttle.recordSuccess("1.2.3.4")
        assertFalse(throttle.isLocked("1.2.3.4"))
    }

    @Test
    fun `other addresses are unaffected`() {
        val throttle = AuthThrottle(maxFailures = 1, windowMillis = 60_000, lockoutMillis = 60_000)
        throttle.recordFailure("10.0.0.1")
        assertTrue(throttle.isLocked("10.0.0.1"))
        assertFalse(throttle.isLocked("10.0.0.2"))
    }
}

class VerifierFileTest {

    private val path = Files.createTempFile("verifier", ".txt")

    @AfterTest
    fun cleanup() {
        Files.deleteIfExists(path)
    }

    @Test
    fun `round trips a verifier without persisting the password`() {
        val verifier = PasswordVerifier.create("hunter2", memoryKib = 16, iterations = 1)
        VerifierFile.save(path, verifier)

        val text = Files.readString(path)
        assertFalse(text.contains("hunter2"), "plaintext password must never be written")

        val loaded = VerifierFile.load(path)
        assertEquals(verifier.kdf.salt, loaded.kdf.salt)
        assertTrue(verifier.storedKey.contentEquals(loaded.storedKey))
        assertTrue(verifier.serverKey.contentEquals(loaded.serverKey))

        val keys = Scram.keys(Scram.deriveAuthKey("hunter2", loaded.kdf))
        val message = Scram.authMessage("alice", ByteArray(16), ByteArray(16))
        assertTrue(Scram.verifyProof(Scram.clientProof(keys, message), loaded.storedKey, message))
    }

    @Test
    fun `rejects a tampered verifier`() {
        val verifier = PasswordVerifier.create("hunter2", memoryKib = 16, iterations = 1)
        VerifierFile.save(path, verifier)
        val tampered = Files.readString(path).replace("storedKey=", "storedKey=AAAA")
        Files.writeString(path, tampered)

        val loaded = VerifierFile.load(path)
        val keys = Scram.keys(Scram.deriveAuthKey("hunter2", loaded.kdf))
        val message = Scram.authMessage("alice", ByteArray(16), ByteArray(16))
        assertFalse(Scram.verifyProof(Scram.clientProof(keys, message), loaded.storedKey, message))
    }
}
