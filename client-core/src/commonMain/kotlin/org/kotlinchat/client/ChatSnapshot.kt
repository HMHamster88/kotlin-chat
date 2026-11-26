package org.kotlinchat.client

import org.kotlinchat.client.rtc.PeerLinkInfo
import org.kotlinchat.client.rtc.VideoFrameData
import org.kotlinchat.client.control.ScreenControlState
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.RoomSummary

/** Voice state for the current room. */
data class VoiceSnapshot(
    /** The room's WebRTC mesh is up (audio, files, screen share). */
    val active: Boolean = false,
    /** The user joined the call. Mute controls are only shown while this is true. */
    val joined: Boolean = false,
    val muted: Boolean = true,
    /** Incoming audio is silenced locally; the microphone is unaffected. */
    val speakerMuted: Boolean = false,
    val localLevel: Float = 0f,
    val links: Map<String, PeerLinkInfo> = emptyMap(),
    /** False when the server supplied no STUN/TURN, so NAT traversal has no fallback. */
    val hasRelay: Boolean = false,
    val error: String? = null,
) {
    val connectedPeers: Int get() = links.values.count { it.state == org.kotlinchat.client.rtc.PeerLinkState.CONNECTED }
}

/** Screen-share state for the current room. */
data class ScreenSnapshot(
    val sharing: Boolean = false,
    /** Latest preview frame of the peer currently presenting. */
    val remote: VideoFrameData? = null,
    val remoteFrom: String? = null,
    val error: String? = null,
)

/** Everything the UI renders, updated atomically by [ChatClient]. */
data class ChatSnapshot(
    val connection: ConnectionState = ConnectionState.Idle,
    val serverName: String = "",
    val motd: String = "",
    val serverFingerprint: String? = null,
    val self: Member? = null,
    val rooms: List<RoomSummary> = emptyList(),
    val room: RoomState? = null,
    /** Authenticated to the server but deliberately outside every room. */
    val roomless: Boolean = false,
    /** Empty client-created rooms the server will currently accept a delete request for. */
    val deletableRoomIds: Set<String> = emptySet(),
    val messages: List<ChatMessage> = emptyList(),
    val lastError: String? = null,
    val latencyMs: Long? = null,
    val maxChatBytes: Int = 4096,
    val voice: VoiceSnapshot = VoiceSnapshot(),
    val screen: ScreenSnapshot = ScreenSnapshot(),
    /** Remote-control state for this desktop client, if any. */
    val control: ScreenControlState = ScreenControlState(),
) {
    val roomId: String? get() = room?.room?.id
    val members: List<Member> get() = room?.members.orEmpty()
}
