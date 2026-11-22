package org.kotlinchat.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Handshake
// ---------------------------------------------------------------------------

@Serializable
data class Hello(
    val name: String,
    val pubkey: String? = null,
    val protocol: Int = Protocol.VERSION,
)

@Serializable
data class KdfParams(
    val alg: String = "argon2id",
    val salt: String,
    val m: Int = 65536,
    val t: Int = 3,
    val p: Int = 1,
)

@Serializable
data class Challenge(
    val nonce: String,
    val kdf: KdfParams,
)

@Serializable
data class AuthRequest(
    val clientNonce: String,
    val proof: String,
)

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

@Serializable
data class ServerLimits(
    val maxChatBytes: Int = 4096,
    val maxMembersPerRoom: Int = 8,
)

@Serializable
data class TurnCredentials(
    val urls: List<String>,
    val username: String,
    val credential: String,
    val ttlSeconds: Int,
)

@Serializable
data class RoomSummary(
    val id: String,
    val name: String,
    val topic: String = "",
    val memberCount: Int = 0,
    val cap: Int = 0,
)

@Serializable
data class Member(
    val sessionId: String,
    val name: String,
    val pubkeyFp: String? = null,
    val joinedAt: Long = 0,
    val selfMute: Boolean = false,
    val selfDeaf: Boolean = false,
    val sharing: Boolean = false,
)

@Serializable
data class Welcome(
    val sessionId: String,
    val serverName: String,
    val motd: String = "",
    val serverSignature: String? = null,
    val rooms: List<RoomSummary> = emptyList(),
    val turn: TurnCredentials? = null,
    val limits: ServerLimits = ServerLimits(),
)

@Serializable
data class RoomState(
    val room: RoomSummary,
    val self: Member,
    val members: List<Member>,
)

@Serializable
data class RoomListResult(
    val rooms: List<RoomSummary>,
)

@Serializable
data class RoomJoinRequest(
    val roomId: String,
)

@Serializable
data class MemberJoined(
    val member: Member,
)

@Serializable
data class MemberLeft(
    val sessionId: String,
    val reason: String,
)

@Serializable
data class MemberUpdate(
    val sessionId: String,
    val selfMute: Boolean,
    val selfDeaf: Boolean,
    val sharing: Boolean,
)

@Serializable
data class ServerShutdown(
    val inMs: Long,
    val reason: String,
)

// ---------------------------------------------------------------------------
// Chat and presence
// ---------------------------------------------------------------------------

@Serializable
data class ChatSend(
    val text: String,
    val clientMsgId: String? = null,
)

@Serializable
data class ChatMessage(
    val messageId: String,
    val roomId: String,
    val from: String,
    val name: String,
    val text: String,
    val ts: Long,
    val clientMsgId: String? = null,
)

@Serializable
data class PresenceUpdate(
    val selfMute: Boolean? = null,
    val selfDeaf: Boolean? = null,
    val sharing: Boolean? = null,
)

// ---------------------------------------------------------------------------
// Signaling
// ---------------------------------------------------------------------------

@Serializable
data class RtcSignal(
    val to: String,
    val signal: SignalBody,
)

@Serializable
data class SignalBody(
    val kind: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

@Serializable
data class RtcSignalRelay(
    val from: String,
    val signal: SignalBody,
)

// ---------------------------------------------------------------------------
// File transfer
// ---------------------------------------------------------------------------

@Serializable
data class FileOffer(
    val transferId: String,
    val name: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class FileAccept(
    val transferId: String,
    val to: String,
)

@Serializable
data class FileDecline(
    val transferId: String,
    val to: String,
    val reason: String? = null,
)

@Serializable
data class FileCancel(
    val transferId: String,
    val to: String? = null,
)

@Serializable
data class FileOfferRelay(
    val from: String,
    val transferId: String,
    val name: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class FileAcceptRelay(
    val from: String,
    val transferId: String,
)

@Serializable
data class FileDeclineRelay(
    val from: String,
    val transferId: String,
    val reason: String? = null,
)

@Serializable
data class FileCancelRelay(
    val from: String,
    val transferId: String,
)

// ---------------------------------------------------------------------------
// Control
// ---------------------------------------------------------------------------

@Serializable
data class Ping(val ts: Long)

@Serializable
data class Pong(val ts: Long, val serverTs: Long)

@Serializable
data class ErrorPayload(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean = false,
    val fatal: Boolean = false,
)

@Serializable
enum class ErrorCode {
    @SerialName("E_PROTO_VERSION") PROTO_VERSION,
    @SerialName("E_BAD_PAYLOAD") BAD_PAYLOAD,
    @SerialName("E_AUTH_REQUIRED") AUTH_REQUIRED,
    @SerialName("E_AUTH_FAILED") AUTH_FAILED,
    @SerialName("E_AUTH_RATE_LIMIT") AUTH_RATE_LIMIT,
    @SerialName("E_NAME_INVALID") NAME_INVALID,
    @SerialName("E_ROOM_NOT_FOUND") ROOM_NOT_FOUND,
    @SerialName("E_ROOM_FULL") ROOM_FULL,
    @SerialName("E_NOT_IN_ROOM") NOT_IN_ROOM,
    @SerialName("E_BAD_TARGET") BAD_TARGET,
    @SerialName("E_RATE_LIMIT") RATE_LIMIT,
    @SerialName("E_PAYLOAD_TOO_LARGE") PAYLOAD_TOO_LARGE,
    @SerialName("E_INTERNAL") INTERNAL,
    @SerialName("E_SHUTTING_DOWN") SHUTTING_DOWN,
}
