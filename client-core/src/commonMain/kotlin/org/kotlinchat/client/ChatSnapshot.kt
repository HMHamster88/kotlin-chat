package org.kotlinchat.client

import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.RoomSummary

/** Everything the UI renders, updated atomically by [ChatClient]. */
data class ChatSnapshot(
    val connection: ConnectionState = ConnectionState.Idle,
    val serverName: String = "",
    val motd: String = "",
    val serverFingerprint: String? = null,
    val self: Member? = null,
    val rooms: List<RoomSummary> = emptyList(),
    val room: RoomState? = null,
    val messages: List<ChatMessage> = emptyList(),
    val lastError: String? = null,
    val latencyMs: Long? = null,
    val maxChatBytes: Int = 4096,
) {
    val roomId: String? get() = room?.room?.id
    val members: List<Member> get() = room?.members.orEmpty()
}
