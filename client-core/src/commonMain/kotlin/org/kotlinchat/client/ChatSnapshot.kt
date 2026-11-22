package org.kotlinchat.client

import org.kotlinchat.client.rtc.PeerLinkInfo
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.RoomSummary

/** Voice state for the current room. */
data class VoiceSnapshot(
    val active: Boolean = false,
    val muted: Boolean = true,
    val localLevel: Float = 0f,
    val links: Map<String, PeerLinkInfo> = emptyMap(),
    val error: String? = null,
) {
    val connectedPeers: Int get() = links.values.count { it.state == org.kotlinchat.client.rtc.PeerLinkState.CONNECTED }
}

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
    val voice: VoiceSnapshot = VoiceSnapshot(),
) {
    val roomId: String? get() = room?.room?.id
    val members: List<Member> get() = room?.members.orEmpty()
}
