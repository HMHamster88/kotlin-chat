package org.kotlinchat.client.rtc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.kotlinchat.protocol.SignalBody

/** An ICE server handed out by the server in `welcome.turn`. */
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/** A capture or playout device, identified by an opaque platform descriptor. */
data class AudioDeviceInfo(val id: String, val name: String)

data class AudioDeviceSelection(
    val inputId: String? = null,
    val outputId: String? = null,
)

data class RtcOptions(
    val iceServers: List<IceServerConfig> = emptyList(),
    val devices: AudioDeviceSelection = AudioDeviceSelection(),
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGainControl: Boolean = true,
)

enum class PeerLinkState { CONNECTING, CONNECTED, FAILED, CLOSED }

data class PeerLinkInfo(
    val state: PeerLinkState = PeerLinkState.CONNECTING,
    val relayed: Boolean = false,
    /** Remote speech level, 0..1, for speaking indicators. */
    val level: Float = 0f,
    /** Inbound RTP bytes, a cheap proof that media is actually flowing. */
    val bytesReceived: Long = 0,
)

data class OutgoingSignal(val to: String, val signal: SignalBody)

/**
 * One live mesh participant set: the local microphone plus one peer connection per remote
 * member. Implementations own all platform WebRTC details; the caller only manages membership
 * and forwards opaque signals.
 */
interface RtcSession : AutoCloseable {
    val localLevel: StateFlow<Float>
    val links: StateFlow<Map<String, PeerLinkInfo>>

    /** SDP and ICE destined for the server. Hot: never suspends the caller. */
    val outgoing: Flow<OutgoingSignal>

    /**
     * Starts a connection. The peer with the smaller session id is impolite and creates the
     * initial offer; its counterpart waits for one.
     */
    fun addPeer(peerId: String, polite: Boolean)

    fun removePeer(peerId: String)

    /** Feeds an SDP/ICE message relayed by the server. */
    fun handleSignal(from: String, signal: SignalBody)

    fun setMuted(muted: Boolean)

    fun setOutputVolume(peerId: String, volume: Float)
}

interface RtcEngine {
    /** Cheap capability probe; false means voice stays disabled but chat still works. */
    fun available(): Boolean

    fun inputDevices(): List<AudioDeviceInfo>

    fun outputDevices(): List<AudioDeviceInfo>

    suspend fun start(options: RtcOptions): RtcSession
}
