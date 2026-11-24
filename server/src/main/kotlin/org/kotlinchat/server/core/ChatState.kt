package org.kotlinchat.server.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.MemberJoined
import org.kotlinchat.protocol.MemberLeft
import org.kotlinchat.protocol.MemberUpdate
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.RoomListResult
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.RoomSummary
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.toHex
import org.kotlinchat.protocol.payloadOf
import org.kotlinchat.server.config.ClientRoomsFile
import org.kotlinchat.server.config.RoomConfig
import org.kotlinchat.server.config.ServerConfig
import java.nio.file.Path

sealed interface JoinResult {
    data object Ok : JoinResult
    data object UnknownRoom : JoinResult
    data object Full : JoinResult
}

private const val MAX_TOPIC_CHARS = 120

/** Outcome of a client-requested room. */
sealed interface CreateRoomResult {
    data class Ok(val room: RoomConfig) : CreateRoomResult
    data object Disabled : CreateRoomResult
    data object Limit : CreateRoomResult
    data object Invalid : CreateRoomResult
    data object NameTaken : CreateRoomResult
}

sealed interface ChatResult {
    data class Ok(val message: ChatMessage) : ChatResult
    data object RateLimited : ChatResult
    data object Invalid : ChatResult
    data object NotInRoom : ChatResult
}

/**
 * All authoritative room state.
 *
 * Every mutation happens under one mutex and performs its broadcasts while holding it, so
 * every member observes join/leave/chat events in the same order.
 */
class ChatState(
    private val config: ServerConfig,
    /** Where client-created rooms are kept; null keeps them in memory (tests). */
    private val clientRoomsPath: Path? = null,
) {

    private val mutex = Mutex()
    private val roomDefs: LinkedHashMap<String, RoomConfig>
    private val clientRoomIds = linkedSetOf<String>()
    private val sessions = mutableMapOf<String, ClientSession>()
    private val rooms = mutableMapOf<String, MutableSet<String>>()

    init {
        val definitions = LinkedHashMap<String, RoomConfig>()
        definitions[ClientSession.LOBBY] =
            RoomConfig(id = ClientSession.LOBBY, name = "Lobby", topic = "Say hello")
        config.rooms.forEach { definitions[it.id] = it }
        clientRoomsPath?.let { path ->
            ClientRoomsFile.load(path).forEach { saved ->
                // A room that now exists in server.yaml wins over the saved copy.
                if (saved.id !in definitions) {
                    definitions[saved.id] = saved
                    clientRoomIds += saved.id
                }
            }
        }
        roomDefs = definitions
        definitions.keys.forEach { rooms[it] = linkedSetOf() }
    }

    /**
     * Adds the session to [joinRoom], or leaves it outside every room when that is null, and
     * publishes its initial state **while holding the lock**, so no `member.joined` for another
     * session can be queued to this client before its own snapshot. Without that ordering, a
     * client can apply a delta before it knows its room and silently drop it.
     */
    suspend fun register(
        session: ClientSession,
        requestedName: String,
        pubkeyFp: String?,
        joinRoom: String? = ClientSession.LOBBY,
        publishInitialState: (RoomState?, List<RoomSummary>) -> Unit,
    ): RoomState? = mutex.withLock {
        session.name = uniqueNameLocked(requestedName)
        session.pubkeyFp = pubkeyFp
        session.joinedAt = System.currentTimeMillis()
        session.authenticated = true
        session.roomId = joinRoom

        sessions[session.id] = session
        joinRoom?.let { rooms.getValue(it).add(session.id) }

        val snapshot = joinRoom?.let { roomStateLocked(it, session) }
        publishInitialState(snapshot, roomDefs.values.map(::summaryLocked))

        if (joinRoom != null) {
            broadcastLocked(
                roomId = joinRoom,
                text = frame(MessageType.MEMBER_JOINED, payloadOf(MemberJoined(session.member()))),
                except = session.id,
            )
        }
        broadcastRoomListLocked()
        snapshot
    }

    suspend fun unregister(session: ClientSession) = mutex.withLock {
        if (sessions.remove(session.id) != null) {
            session.roomId?.let { previous ->
                rooms[previous]?.remove(session.id)
                broadcastLocked(
                    roomId = previous,
                    text = frame(MessageType.MEMBER_LEFT, payloadOf(MemberLeft(session.id, "disconnect"))),
                    except = session.id,
                )
            }
            broadcastRoomListLocked()
        }
    }

    /**
     * Drops the session out of its room without closing the connection. The client receives the
     * new room list through [publishLeft]; everyone still in the old room sees a normal leave.
     */
    suspend fun leave(
        session: ClientSession,
        publishLeft: (List<RoomSummary>) -> Unit,
    ) = mutex.withLock {
        val previous = session.roomId
        session.roomId = null
        session.sharing = false
        if (previous != null) {
            rooms[previous]?.remove(session.id)
            publishLeft(roomDefs.values.map(::summaryLocked))
            broadcastLocked(
                roomId = previous,
                text = frame(MessageType.MEMBER_LEFT, payloadOf(MemberLeft(session.id, "leave"))),
                except = session.id,
            )
        } else {
            publishLeft(roomDefs.values.map(::summaryLocked))
        }
        broadcastRoomListLocked()
    }

    suspend fun join(
        session: ClientSession,
        roomId: String,
        publishState: (RoomState) -> Unit,
    ): JoinResult = mutex.withLock {
        val definition = roomDefs[roomId] ?: return JoinResult.UnknownRoom
        val target = rooms[roomId] ?: return JoinResult.UnknownRoom
        if (session.roomId == roomId) {
            publishState(roomStateLocked(roomId, session))
            return JoinResult.Ok
        }
        if (definition.voice && target.size >= config.maxUsersPerRoom) return JoinResult.Full

        val previous = session.roomId
        previous?.let { rooms[it]?.remove(session.id) }
        target.add(session.id)
        session.roomId = roomId

        publishState(roomStateLocked(roomId, session))

        if (previous != null) {
            broadcastLocked(
                roomId = previous,
                text = frame(MessageType.MEMBER_LEFT, payloadOf(MemberLeft(session.id, "leave"))),
                except = session.id,
            )
        }
        broadcastLocked(
            roomId = roomId,
            text = frame(MessageType.MEMBER_JOINED, payloadOf(MemberJoined(session.member()))),
            except = session.id,
        )
        broadcastRoomListLocked()
        JoinResult.Ok
    }

    /**
     * Creates a room on behalf of a member and moves them into it in the same critical section,
     * so nobody observes a room that exists but has no owner. The room list change is broadcast
     * to everyone because the list is part of every client's snapshot.
     */
    suspend fun createRoom(
        session: ClientSession,
        name: String,
        topic: String,
        voice: Boolean,
        publishState: (RoomState) -> Unit,
    ): CreateRoomResult = mutex.withLock {
        if (!config.allowClientRooms) return CreateRoomResult.Disabled
        if (roomDefs.size >= config.maxRooms) return CreateRoomResult.Limit

        val display = name.trim()
        if (display.isEmpty() || display.length > 32 || display.any { it.isISOControl() }) {
            return CreateRoomResult.Invalid
        }
        if (roomDefs.values.any { it.name.equals(display, ignoreCase = true) }) {
            return CreateRoomResult.NameTaken
        }

        val definition = RoomConfig(
            id = uniqueRoomIdLocked(display),
            name = display,
            topic = topic.trim().take(MAX_TOPIC_CHARS),
            voice = voice,
        )
        roomDefs[definition.id] = definition
        rooms[definition.id] = linkedSetOf()
        clientRoomIds += definition.id
        persistClientRoomsLocked()

        val previous = session.roomId
        previous?.let { rooms[it]?.remove(session.id) }
        rooms.getValue(definition.id).add(session.id)
        session.roomId = definition.id
        publishState(roomStateLocked(definition.id, session))
        if (previous != null) {
            broadcastLocked(
                roomId = previous,
                text = frame(MessageType.MEMBER_LEFT, payloadOf(MemberLeft(session.id, "leave"))),
                except = session.id,
            )
        }
        broadcastLocked(
            roomId = definition.id,
            text = frame(MessageType.MEMBER_JOINED, payloadOf(MemberJoined(session.member()))),
            except = session.id,
        )
        // After the creator is in, so the counts in the new list are right.
        broadcastRoomListLocked()
        CreateRoomResult.Ok(definition)
    }

    suspend fun updatePresence(        session: ClientSession,
        selfMute: Boolean?,
        selfDeaf: Boolean?,
        sharing: Boolean?,
    ) = mutex.withLock {
        selfMute?.let { session.selfMute = it }
        selfDeaf?.let { session.selfDeaf = it }
        sharing?.let { session.sharing = it }
        val roomId = session.roomId ?: return@withLock
        broadcastLocked(
            roomId = roomId,
            text = frame(
                MessageType.MEMBER_UPDATED,
                payloadOf(
                    MemberUpdate(
                        sessionId = session.id,
                        selfMute = session.selfMute,
                        selfDeaf = session.selfDeaf,
                        sharing = session.sharing,
                    ),
                ),
            ),
        )
    }

    suspend fun postChat(session: ClientSession, text: String, clientMsgId: String?): ChatResult =
        mutex.withLock {
            val roomId = session.roomId ?: return ChatResult.NotInRoom
            if (!session.chatLimiter.tryAcquire()) return ChatResult.RateLimited

            val body = text.trim()
            if (body.isEmpty() || body.encodeToByteArray().size > config.maxChatBytes) {
                return ChatResult.Invalid
            }

            val message = ChatMessage(
                messageId = "m_" + Crypto.randomBytes(8).toHex(),
                roomId = roomId,
                from = session.id,
                name = session.name,
                text = body,
                ts = System.currentTimeMillis(),
                clientMsgId = clientMsgId,
            )
            broadcastLocked(roomId, frame(MessageType.CHAT_MESSAGE, payloadOf(message)))
            ChatResult.Ok(message)
        }

    suspend fun roomState(session: ClientSession): RoomState? = mutex.withLock {
        session.roomId?.let { roomStateLocked(it, session) }
    }

    suspend fun roomsSummary(): List<RoomSummary> = mutex.withLock {
        roomDefs.values.map(::summaryLocked)
    }

    suspend fun membersOf(roomId: String): List<Member> = mutex.withLock {
        rooms[roomId]?.mapNotNull { sessions[it]?.member() }.orEmpty()
    }

    suspend fun findSession(sessionId: String): ClientSession? = mutex.withLock {
        sessions[sessionId]
    }

    suspend fun broadcast(roomId: String, text: String, except: String? = null) = mutex.withLock {
        broadcastLocked(roomId, text, except)
    }

    suspend fun sessionCount(): Int = mutex.withLock { sessions.size }

    // ---------------------------------------------------------------- internals

    private fun summaryLocked(definition: RoomConfig): RoomSummary = RoomSummary(
        id = definition.id,
        name = definition.name,
        topic = definition.topic,
        memberCount = rooms[definition.id]?.size ?: 0,
        cap = if (definition.voice) config.maxUsersPerRoom else 0,
    )

    private fun roomStateLocked(roomId: String, self: ClientSession): RoomState {
        val definition = roomDefs.getValue(roomId)
        val members = rooms.getValue(roomId).mapNotNull { sessions[it]?.member() }
        return RoomState(room = summaryLocked(definition), self = self.member(), members = members)
    }

    private fun uniqueNameLocked(base: String): String {
        val taken = sessions.values.mapTo(mutableSetOf()) { it.name }
        if (base !in taken) return base
        var suffix = 2
        while ("$base ($suffix)" in taken) suffix++
        return "$base ($suffix)"
    }

    /** A stable, readable id from the display name, made unique against existing rooms. */
    private fun uniqueRoomIdLocked(name: String): String {
        val base = name.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(24)
            .ifBlank { "room" }

        var candidate = base
        var suffix = 2
        while (candidate in roomDefs) {
            candidate = "$base-$suffix"
            suffix++
        }
        return candidate
    }

    private fun persistClientRoomsLocked() {
        val path = clientRoomsPath ?: return
        val saved = clientRoomIds.mapNotNull { roomDefs[it] }
        runCatching { ClientRoomsFile.save(path, saved) }
    }

    /** Member counts are part of the list, so every join/leave refreshes it for everyone. */
    private fun broadcastRoomListLocked() {
        val list = frame(MessageType.ROOM_LIST_RESULT, payloadOf(RoomListResult(roomDefs.values.map(::summaryLocked))))
        sessions.values.forEach { it.send(list) }
    }

    private fun broadcastLocked(roomId: String, text: String, except: String? = null) {
        rooms[roomId]?.forEach { sessionId ->
            if (sessionId != except) sessions[sessionId]?.send(text)
        }
    }

    private fun frame(type: String, payload: JsonObject): String =
        Envelope(type = type, payload = payload).encode()
}
