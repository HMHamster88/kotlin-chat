package org.kotlinchat.media.android

import com.shepeliev.webrtckmp.AudioTrack
import com.shepeliev.webrtckmp.DataChannel
import com.shepeliev.webrtckmp.DataChannelState
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.MediaStreamTrack
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.PeerConnectionState
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.RtpTransceiver
import com.shepeliev.webrtckmp.RtpTransceiverDirection
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.audioTracks
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onDataChannel
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.videoTracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kotlinchat.client.rtc.AudioDeviceInfo
import org.kotlinchat.client.rtc.OutgoingSignal
import org.kotlinchat.client.rtc.PeerDataChannel
import org.kotlinchat.client.rtc.PeerLinkInfo
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.client.rtc.RtcEngine
import org.kotlinchat.client.rtc.RtcOptions
import org.kotlinchat.client.rtc.RtcSession
import org.kotlinchat.client.rtc.ScreenShareSource
import org.kotlinchat.client.rtc.VideoFrameData
import org.kotlinchat.protocol.SignalBody
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Android voice and screen share via webrtc-kmp.
 *
 * Android cannot create a video transceiver before the user grants MediaProjection, so it never
 * offers a video slot: the desktop side (which advertises `video-slot`) always offers, and
 * Android attaches its display track to the negotiated m-line later with `replaceTrack`.
 * Android↔Android is therefore audio-only.
 */
class AndroidRtcEngine : RtcEngine {

    override fun available(): Boolean = true

    override val canOfferVideoSlot: Boolean get() = false

    override fun inputDevices(): List<AudioDeviceInfo> = emptyList()

    override fun outputDevices(): List<AudioDeviceInfo> = emptyList()

    override fun screenSources(): List<ScreenShareSource> = emptyList()

    override suspend fun start(options: RtcOptions): RtcSession {
        val stream = MediaDevices.getUserMedia(audio = true, video = false)
        val audio = stream.audioTracks.firstOrNull() ?: error("microphone unavailable")

        val iceServers = options.iceServers.map {
            IceServer(
                urls = it.urls,
                username = it.username.orEmpty(),
                password = it.credential.orEmpty(),
            )
        }
        return AndroidRtcSession(stream, audio, RtcConfiguration(iceServers = iceServers))
    }
}

private class AndroidRtcSession(
    private val audioStream: MediaStream,
    private val audioTrack: AudioTrack,
    private val configuration: RtcConfiguration,
) : RtcSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _localLevel = MutableStateFlow(0f)
    override val localLevel: StateFlow<Float> = _localLevel.asStateFlow()

    private val _links = MutableStateFlow<Map<String, PeerLinkInfo>>(emptyMap())
    override val links: StateFlow<Map<String, PeerLinkInfo>> = _links.asStateFlow()

    private val _outgoing = MutableSharedFlow<OutgoingSignal>(extraBufferCapacity = 256)
    override val outgoing: Flow<OutgoingSignal> = _outgoing.asSharedFlow()

    private val _sharing = MutableStateFlow(false)
    override val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    /** Android renders no incoming video in M6. */
    private val _remoteFrames = MutableStateFlow<Map<String, VideoFrameData>>(emptyMap())
    override val remoteFrames: StateFlow<Map<String, VideoFrameData>> = _remoteFrames.asStateFlow()

    private val _dataChannels = MutableStateFlow<Map<String, PeerDataChannel>>(emptyMap())
    override val dataChannels: StateFlow<Map<String, PeerDataChannel>> = _dataChannels.asStateFlow()

    private val peers = ConcurrentHashMap<String, PeerLink>()
    private val linkStates = ConcurrentHashMap<String, PeerLinkInfo>()

    override fun addPeer(peerId: String, polite: Boolean) {
        if (peers.containsKey(peerId)) return
        val link = PeerLink(peerId, polite)
        peers[peerId] = link
        linkStates[peerId] = PeerLinkInfo()
        publishLinks()
        link.start()
    }

    override fun removePeer(peerId: String) {
        peers.remove(peerId)?.close()
    }

    override fun handleSignal(from: String, signal: SignalBody) {
        peers[from]?.enqueue(signal)
    }

    override fun setMuted(muted: Boolean) {
        audioTrack.enabled = !muted
    }

    override fun setOutputVolume(peerId: String, volume: Float) {
        // Per-peer volume needs a custom audio path; not available through this API.
    }

    override suspend fun startScreenShare(source: ScreenShareSource, maxWidth: Int, maxHeight: Int, fps: Int) {
        Log.w(TAG, "screen sharing is not supported on Android")
    }

    override fun stopScreenShare() = Unit

    override fun close() {
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        linkStates.clear()
        publishLinks()
        scope.cancel()
        runCatching { audioTrack.stop() }
        runCatching { audioStream.release() }
    }

    private fun publishLinks() {
        _links.value = linkStates.toMap()
    }

    private fun updateLink(peerId: String, transform: (PeerLinkInfo) -> PeerLinkInfo) {
        if (!peers.containsKey(peerId)) return
        linkStates.compute(peerId) { _, existing -> transform(existing ?: PeerLinkInfo()) }
        _links.value = linkStates.toMap()
    }

    private fun emit(to: String, signal: SignalBody) {
        _outgoing.tryEmit(OutgoingSignal(to, signal))
    }

    private inner class PeerLink(val id: String, val polite: Boolean) {

        val pc = PeerConnection(configuration)
        private val queue = Mutex()
        private val inbox = Channel<SignalBody>(Channel.UNLIMITED)
        private val pendingCandidates = mutableListOf<IceCandidate>()
        private var remoteDescriptionSet = false
        private var dataLink: DataChannelLink? = null

        fun start() {
            scope.launch {
                pc.onIceCandidate.collect { candidate ->
                    emit(
                        id,
                        SignalBody(
                            kind = "ice",
                            candidate = candidate.candidate,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                        ),
                    )
                }
            }
            scope.launch {
                pc.onConnectionStateChange.collect { state -> onState(state) }
            }
            scope.launch {
                pc.onDataChannel.collect { channel -> bindChannel(channel) }
            }
            scope.launch {
                for (signal in inbox) {
                    runCatching { process(signal) }
                }
            }
            if (!polite) scope.launch { negotiate() }
        }

        fun enqueue(signal: SignalBody) {
            inbox.trySend(signal)
        }

        fun close() {
            inbox.close()
            dataLink?.let { link ->
                _dataChannels.update { it - id }
                runCatching { link.close() }
            }
            dataLink = null
            runCatching { pc.close() }
            linkStates.remove(id)
            publishLinks()
        }

        private fun onState(state: PeerConnectionState) {
            val mapped = when (state) {
                PeerConnectionState.Connected -> PeerLinkState.CONNECTED
                PeerConnectionState.Failed -> PeerLinkState.FAILED
                PeerConnectionState.Closed -> PeerLinkState.CLOSED
                else -> PeerLinkState.CONNECTING
            }
            updateLink(id) { it.copy(state = mapped) }
        }

        private fun bindChannel(channel: DataChannel) {
            if (dataLink != null) return
            val link = DataChannelLink(id, channel, scope)
            dataLink = link
            _dataChannels.update { it + (id to link) }
        }

        private suspend fun negotiate() {
            queue.withLock {
                if (pc.getTransceivers().isEmpty()) {
                    pc.addTrack(audioTrack)
                    pc.createDataChannel(DATA_CHANNEL_LABEL)?.let { bindChannel(it) }
                }
                val offer = pc.createOffer(OfferAnswerOptions())
                pc.setLocalDescription(offer)
                emit(id, SignalBody(kind = "offer", sdp = offer.sdp))
            }
        }

        private suspend fun process(signal: SignalBody) {
            when (signal.kind) {
                "offer", "answer" -> applyDescription(signal)
                "ice" -> applyCandidate(signal)
                "bye" -> close()
            }
        }

        private suspend fun applyDescription(signal: SignalBody) {
            val sdp = signal.sdp ?: return
            val isOffer = signal.kind == "offer"

            queue.withLock {
                val description = SessionDescription(
                    if (isOffer) SessionDescriptionType.Offer else SessionDescriptionType.Answer,
                    sdp,
                )
                pc.setRemoteDescription(description)
                remoteDescriptionSet = true
                pendingCandidates.forEach { runCatching { pc.addIceCandidate(it) } }
                pendingCandidates.clear()

                if (isOffer) {
                    attachLocalMedia()
                    val answer = pc.createAnswer(OfferAnswerOptions())
                    pc.setLocalDescription(answer)
                    emit(id, SignalBody(kind = "answer", sdp = answer.sdp))
                }
            }
        }

        /**
         * The desktop side creates both m-lines. Attach the microphone to the audio one and
         * mark the video one sendrecv so a display track can be attached later without SDP
         * changes.
         */
        private suspend fun attachLocalMedia() {
            pc.getTransceivers().forEach { transceiver ->
                when (transceiver.receiver.track?.kind) {
                    MediaStreamTrackKind.Audio -> {
                        runCatching { transceiver.sender.replaceTrack(audioTrack) }
                        transceiver.direction = RtpTransceiverDirection.SendRecv
                    }

                    MediaStreamTrackKind.Video -> {
                        // No screen sharing on Android, and no renderer for incoming video:
                        // keep the m-line inactive so the peer sends nothing.
                        transceiver.direction = RtpTransceiverDirection.Inactive
                    }

                    null -> Unit
                }
            }
        }

        private suspend fun applyCandidate(signal: SignalBody) {
            val sdp = signal.candidate ?: return
            val candidate = IceCandidate(
                signal.sdpMid ?: "0",
                signal.sdpMLineIndex ?: 0,
                sdp,
            )
            queue.withLock {
                if (remoteDescriptionSet) {
                    runCatching { pc.addIceCandidate(candidate) }
                } else if (pendingCandidates.size < MAX_PENDING_CANDIDATES) {
                    pendingCandidates.add(candidate)
                }
            }
        }
    }
}

private class DataChannelLink(
    override val peerId: String,
    private val channel: DataChannel,
    private val scope: CoroutineScope,
) : PeerDataChannel {

    private val incomingChannel = Channel<ByteArray>(capacity = 64)
    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    private val _open = MutableStateFlow(channel.readyState == DataChannelState.Open)
    override val open: StateFlow<Boolean> = _open.asStateFlow()

    init {
        scope.launch { channel.onOpen.collect { _open.value = true } }
        scope.launch {
            channel.onClose.collect {
                _open.value = false
                incomingChannel.close()
            }
        }
        scope.launch { channel.onMessage.collect { incomingChannel.trySend(it) } }
    }

    override suspend fun send(bytes: ByteArray): Boolean {
        var spins = 0
        while (!open.value) {
            if (spins++ > OPEN_WAIT_SPINS) return false
            delay(OPEN_WAIT_MS)
        }

        spins = 0
        while (channel.bufferedAmount > HIGH_WATER_BYTES) {
            if (spins++ > BACKPRESSURE_SPINS) return false
            delay(50)
        }

        return runCatching { channel.send(bytes) }.getOrDefault(false)
    }

    override fun close() {
        runCatching { channel.close() }
        incomingChannel.close()
    }
}

private const val DATA_CHANNEL_LABEL = "files"
private const val TAG = "AndroidRtc"
private const val HIGH_WATER_BYTES = 4L * 1024 * 1024
private const val OPEN_WAIT_SPINS = 100
private const val OPEN_WAIT_MS = 100L
private const val BACKPRESSURE_SPINS = 600
private const val MAX_PENDING_CANDIDATES = 256
