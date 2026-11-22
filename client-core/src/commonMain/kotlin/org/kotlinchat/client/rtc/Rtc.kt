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
 * One reliable, ordered data channel per peer, negotiated with the media slots so file
 * transfer never renegotiates either.
 */
interface PeerDataChannel {
    val peerId: String

    /** Emits when the channel transitions; false until SCTP is established. */
    val open: StateFlow<Boolean>

    /** Suspends while the SCTP send buffer is full. */
    suspend fun send(bytes: ByteArray): Boolean

    /** Raw chunks in order, as they arrive. */
    val incoming: Flow<ByteArray>

    fun close()
}

/** A screen or window that can be shared. */
data class ScreenShareSource(
    val id: Long,
    val title: String,
    val isWindow: Boolean,
)

/** One decoded frame, already converted to RGBA for the UI. */
data class VideoFrameData(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val sequence: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is VideoFrameData && other.sequence == sequence && other.width == width && other.height == height

    override fun hashCode(): Int = sequence.hashCode() * 31 + width * 17 + height
}

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

    /** True between [startScreenShare] and [stopScreenShare]. */
    val sharing: StateFlow<Boolean>

    /** Latest preview frame per sharing peer, already downscaled for the UI. */
    val remoteFrames: StateFlow<Map<String, VideoFrameData>>

    /** Data channels that are currently available, keyed by peer session id. */
    val dataChannels: StateFlow<Map<String, PeerDataChannel>>

    /**
     * Starts desktop capture into the video slot that was negotiated with every peer at
     * connection time, so sharing never triggers renegotiation.
     */
    fun startScreenShare(source: ScreenShareSource, maxWidth: Int = 1280, maxHeight: Int = 720, fps: Int = 10)

    fun stopScreenShare()
}

interface RtcEngine {
    /** Cheap capability probe; false means voice stays disabled but chat still works. */
    fun available(): Boolean

    fun inputDevices(): List<AudioDeviceInfo>

    fun outputDevices(): List<AudioDeviceInfo>

    /** Screens and windows available for sharing; empty when capture is unsupported. */
    fun screenSources(): List<ScreenShareSource>

    suspend fun start(options: RtcOptions): RtcSession
}
