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
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.RoomSummary
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.toHex
import org.kotlinchat.protocol.payloadOf
import org.kotlinchat.server.config.RoomConfig
import org.kotlinchat.server.config.ServerConfig

sealed interface JoinResult {
    data object Ok : JoinResult
    data object UnknownRoom : JoinResult
    data object Full : JoinResult
}

sealed interface ChatResult {
    data class Ok(val message: ChatMessage) : ChatResult
    data object RateLimited : ChatResult
    data object Invalid : ChatResult
}

/**
 * All authoritative room state.
 *
 * Every mutation happens under one mutex and performs its broadcasts while holding it, so
 * every member observes join/leave/chat events in the same order.
 */
class ChatState(private val config: ServerConfig) {

    private val mutex = Mutex()
    private val roomDefs: Map<String, RoomConfig>
    private val sessions = mutableMapOf<String, ClientSession>()
    private val rooms = mutableMapOf<String, MutableSet<String>>()

    init {
        val definitions = LinkedHashMap<String, RoomConfig>()
        definitions[ClientSession.LOBBY] =
            RoomConfig(id = ClientSession.LOBBY, name = "Lobby", topic = "Say hello")
        config.rooms.forEach { definitions[it.id] = it }
        roomDefs = definitions
        definitions.keys.forEach { rooms[it] = linkedSetOf() }
    }

    /**
     * Adds the session to the lobby and publishes its initial state **while holding the
     * lock**, so no `member.joined` for another session can be queued to this client
     * before its own snapshot. Without that ordering, a client can apply a delta before
     * it knows its room and silently drop it.
     */
    suspend fun register(
        session: ClientSession,
        requestedName: String,
        pubkeyFp: String?,
        publishInitialState: (RoomState, List<RoomSummary>) -> Unit,
    ): RoomState = mutex.withLock {
        session.name = uniqueNameLocked(requestedName)
        session.pubkeyFp = pubkeyFp
        session.joinedAt = System.currentTimeMillis()
        session.authenticated = true
        session.roomId = ClientSession.LOBBY

        sessions[session.id] = session
        rooms.getValue(ClientSession.LOBBY).add(session.id)

        val snapshot = roomStateLocked(ClientSession.LOBBY, session)
        publishInitialState(snapshot, roomDefs.values.map(::summaryLocked))

        broadcastLocked(
            roomId = ClientSession.LOBBY,
            text = frame(MessageType.MEMBER_JOINED, payloadOf(MemberJoined(session.member()))),
            except = session.id,
        )
        snapshot
    }

    suspend fun unregister(session: ClientSession) = mutex.withLock {
        if (sessions.remove(session.id) != null) {
            rooms[session.roomId]?.remove(session.id)
            broadcastLocked(
                roomId = session.roomId,
                text = frame(MessageType.MEMBER_LEFT, payloadOf(MemberLeft(session.id, "disconnect"))),
                except = session.id,
            )
        }
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
        rooms[previous]?.remove(session.id)
        target.add(session.id)
        session.roomId = roomId

        publishState(roomStateLocked(roomId, session))

        broadcastLocked(
            roomId = previous,
            text = frame(MessageType.MEMBER_LEFT, payloadOf(MemberLeft(session.id, "leave"))),
            except = session.id,
        )
        broadcastLocked(
            roomId = roomId,
            text = frame(MessageType.MEMBER_JOINED, payloadOf(MemberJoined(session.member()))),
            except = session.id,
        )
        JoinResult.Ok
    }

    suspend fun updatePresence(
        session: ClientSession,
        selfMute: Boolean?,
        selfDeaf: Boolean?,
        sharing: Boolean?,
    ) = mutex.withLock {
        selfMute?.let { session.selfMute = it }
        selfDeaf?.let { session.selfDeaf = it }
        sharing?.let { session.sharing = it }
        broadcastLocked(
            roomId = session.roomId,
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
            if (!session.chatLimiter.tryAcquire()) return ChatResult.RateLimited

            val body = text.trim()
            if (body.isEmpty() || body.encodeToByteArray().size > config.maxChatBytes) {
                return ChatResult.Invalid
            }

            val message = ChatMessage(
                messageId = "m_" + Crypto.randomBytes(8).toHex(),
                roomId = session.roomId,
                from = session.id,
                name = session.name,
                text = body,
                ts = System.currentTimeMillis(),
                clientMsgId = clientMsgId,
            )
            broadcastLocked(session.roomId, frame(MessageType.CHAT_MESSAGE, payloadOf(message)))
            ChatResult.Ok(message)
        }

    suspend fun roomState(session: ClientSession): RoomState = mutex.withLock {
        roomStateLocked(session.roomId, session)
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

    private fun broadcastLocked(roomId: String, text: String, except: String? = null) {
        rooms[roomId]?.forEach { sessionId ->
            if (sessionId != except) sessions[sessionId]?.send(text)
        }
    }

    private fun frame(type: String, payload: JsonObject): String =
        Envelope(type = type, payload = payload).encode()
}
