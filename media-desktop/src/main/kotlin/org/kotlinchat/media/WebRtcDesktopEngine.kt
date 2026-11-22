package org.kotlinchat.media

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCIceTransportPolicy
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.RTCRtcpMuxPolicy
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.RTCStatsCollectorCallback
import dev.onvoid.webrtc.RTCStatsReport
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kotlinchat.client.rtc.AudioDeviceInfo
import org.kotlinchat.client.rtc.OutgoingSignal
import org.kotlinchat.client.rtc.PeerLinkInfo
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.client.rtc.RtcEngine
import org.kotlinchat.client.rtc.RtcOptions
import org.kotlinchat.client.rtc.RtcSession
import org.kotlinchat.protocol.SignalBody
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Desktop voice via libwebrtc (webrtc-java).
 *
 * Mesh design: one [RTCPeerConnection] per room member, a single local audio track shared by
 * all of them, and one designated offerer per pair (the larger session id) so glare cannot
 * occur. Renegotiation would need the full perfect-negotiation dance; nothing adds or removes
 * tracks after the initial exchange in M2.
 */
class WebRtcDesktopEngine : RtcEngine {

    override fun available(): Boolean = runCatching {
        Class.forName("dev.onvoid.webrtc.PeerConnectionFactory")
        MediaDevices.getAudioCaptureDevices().isNotEmpty()
    }.getOrDefault(false)

    override fun inputDevices(): List<AudioDeviceInfo> = runCatching {
        MediaDevices.getAudioCaptureDevices().map { AudioDeviceInfo(it.descriptor, it.name) }
    }.getOrDefault(emptyList())

    override fun outputDevices(): List<AudioDeviceInfo> = runCatching {
        MediaDevices.getAudioRenderDevices().map { AudioDeviceInfo(it.descriptor, it.name) }
    }.getOrDefault(emptyList())

    override suspend fun start(options: RtcOptions): RtcSession = withContext(Dispatchers.IO) {
        val adm = AudioDeviceModule()
        try {
            options.devices.inputId?.let { wanted ->
                MediaDevices.getAudioCaptureDevices()
                    .firstOrNull { it.descriptor == wanted }
                    ?.let(adm::setRecordingDevice)
            }
            options.devices.outputId?.let { wanted ->
                MediaDevices.getAudioRenderDevices()
                    .firstOrNull { it.descriptor == wanted }
                    ?.let(adm::setPlayoutDevice)
            }

            val factory = PeerConnectionFactory(adm)
            try {
                // A custom ADM is externally managed: the factory attaches it but does not
                // start capture/playout threads.
                adm.initPlayout()
                adm.startPlayout()
                adm.initRecording()
                adm.startRecording()

                val audioOptions = AudioOptions().apply {
                    echoCancellation = options.echoCancellation
                    noiseSuppression = options.noiseSuppression
                    autoGainControl = options.autoGainControl
                }
                val source = factory.createAudioSource(audioOptions)
                val track = factory.createAudioTrack("mic", source)

                val iceServers = options.iceServers.map { server ->
                    RTCIceServer().apply {
                        urls = server.urls
                        username = server.username
                        password = server.credential
                    }
                }
                WebRtcSession(factory, adm, track, iceServers)
            } catch (t: Throwable) {
                factory.dispose()
                throw t
            }
        } catch (t: Throwable) {
            adm.dispose()
            throw t
        }
    }
}

private class WebRtcSession(
    private val factory: PeerConnectionFactory,
    private val adm: AudioDeviceModule,
    private val localTrack: AudioTrack,
    iceServers: List<RTCIceServer>,
) : RtcSession {

    private val log = LoggerFactory.getLogger(WebRtcSession::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)

    private val _localLevel = MutableStateFlow(0f)
    override val localLevel: StateFlow<Float> = _localLevel.asStateFlow()

    private val _links = MutableStateFlow<Map<String, PeerLinkInfo>>(emptyMap())
    override val links: StateFlow<Map<String, PeerLinkInfo>> = _links.asStateFlow()

    private val _outgoing = MutableSharedFlow<OutgoingSignal>(extraBufferCapacity = 256)
    override val outgoing: Flow<OutgoingSignal> = _outgoing.asSharedFlow()

    private val peers = ConcurrentHashMap<String, PeerLink>()
    private val linkStates = ConcurrentHashMap<String, PeerLinkInfo>()

    private val configuration = RTCConfiguration().apply {
        this.iceServers = iceServers
        bundlePolicy = RTCBundlePolicy.MAX_BUNDLE
        rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE
        iceTransportPolicy = RTCIceTransportPolicy.ALL
    }

    init {
        scope.launch { levelLoop() }
    }

    override fun addPeer(peerId: String, polite: Boolean) {
        if (closed.get() || peers.containsKey(peerId)) return
        log.info("adding peer {} (polite={})", peerId, polite)
        val link = PeerLink(peerId, polite)
        peers[peerId] = link
        linkStates[peerId] = PeerLinkInfo()
        publishLinks()

        scope.launch {
            runCatching {
                if (!polite) link.negotiate()
            }.onFailure { log.warn("peer {}: initial negotiation failed: {}", peerId, it.message) }
        }
    }

    override fun removePeer(peerId: String) {
        peers.remove(peerId)?.close()
    }

    override fun handleSignal(from: String, signal: SignalBody) {
        peers[from]?.enqueue(signal)
    }

    override fun setMuted(muted: Boolean) {
        runCatching { localTrack.setEnabled(!muted) }
            .onFailure { log.warn("could not toggle microphone: {}", it.message) }
    }

    override fun setOutputVolume(peerId: String, volume: Float) {
        // Per-peer volume needs a sink per remote track; deferred to a later milestone.
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        linkStates.clear()
        publishLinks()
        scope.cancel()
        runCatching { localTrack.dispose() }
        runCatching { factory.dispose() }
        runCatching { adm.dispose() }
    }

    // ------------------------------------------------------------------ internals

    private fun publishLinks() {
        _links.value = linkStates.toMap()
    }

    private fun updateLink(peerId: String, transform: (PeerLinkInfo) -> PeerLinkInfo) {
        if (!peers.containsKey(peerId)) return
        val updated = linkStates.compute(peerId) { _, existing ->
            transform(existing ?: PeerLinkInfo())
        } ?: return
        _links.value = linkStates.toMap()
        if (updated.state == PeerLinkState.CONNECTED) {
            peers[peerId]?.startStats()
        }
    }

    private suspend fun levelLoop() {
        while (currentCoroutineContext().isActive && !closed.get()) {
            delay(120)
            _localLevel.value = (localTrack.signalLevel / 32768f).coerceIn(0f, 1f)
            var changed = false
            peers.values.forEach { link ->
                val level = link.remoteLevel()
                val existing = linkStates[link.id] ?: return@forEach
                if (existing.level != level) {
                    linkStates[link.id] = existing.copy(level = level)
                    changed = true
                }
            }
            if (changed) publishLinks()
        }
    }

    private fun emit(to: String, signal: SignalBody) {
        if (!_outgoing.tryEmit(OutgoingSignal(to, signal))) {
            log.warn("dropped outgoing {} for {}: buffer full", signal.kind, to)
        }
    }

    private inner class PeerLink(val id: String, val polite: Boolean) {

        private val queue = Mutex()
        private val inbox = Channel<SignalBody>(Channel.UNLIMITED)
        private var makingOffer = false
        private var remoteDescriptionSet = false
        private val pendingCandidates = mutableListOf<RTCIceCandidate>()
        private var transceiver: RTCRtpTransceiver? = null
        private var receiver: RTCRtpReceiver? = null
        private var statsJob: Job? = null

        private val observer = object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                emit(
                    id,
                    SignalBody(
                        kind = "ice",
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
                )
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                val mapped = when (state) {
                    RTCPeerConnectionState.CONNECTED -> PeerLinkState.CONNECTED
                    RTCPeerConnectionState.FAILED -> PeerLinkState.FAILED
                    RTCPeerConnectionState.CLOSED -> PeerLinkState.CLOSED
                    else -> PeerLinkState.CONNECTING
                }
                log.info("peer {}: connection {}", id, state)
                updateLink(id) { it.copy(state = mapped) }
            }

            override fun onTrack(transceiver: RTCRtpTransceiver) {
                receiver = transceiver.receiver
            }
        }

        val pc: RTCPeerConnection = factory.createPeerConnection(configuration, observer)

        init {
            scope.launch {
                for (signal in inbox) {
                    runCatching { process(signal) }
                        .onFailure { log.warn("peer {}: failed to apply {}: {}", id, signal.kind, it.message) }
                }
            }
        }

        fun enqueue(signal: SignalBody) {
            inbox.trySend(signal)
        }

        suspend fun negotiate() {
            queue.withLock {
                makingOffer = true
                try {
                    ensureLocalAudio()
                    val offer = pc.createOfferAwait()
                    pc.setLocalDescription(offer)
                    log.debug("peer {}: sending offer ({} chars)", id, offer.sdp.length)
                    emit(id, SignalBody(kind = "offer", sdp = offer.sdp))
                } finally {
                    makingOffer = false
                }
            }
        }

        fun remoteLevel(): Float {
            val current = receiver ?: return 0f
            return runCatching {
                current.synchronizationSources.maxOfOrNull { it.audioLevel.toFloat() } ?: 0f
            }.getOrDefault(0f)
        }

        fun startStats() {
            if (statsJob?.isActive == true) return
            statsJob = scope.launch {
                while (currentCoroutineContext().isActive) {
                    val report = runCatching { pc.statsAwait() }.getOrNull()
                    if (report != null) {
                        val inbound = report.stats.values.filter { it.type == RTCStatsType.INBOUND_RTP }
                        if (inbound.isNotEmpty()) {
                            log.debug(
                                "peer {}: inbound rtp {}",
                                id,
                                inbound.joinToString { "bytes=${it.members["bytesReceived"]} kind=${it.members["kind"]}" },
                            )
                        }
                        updateLink(id) {
                            it.copy(
                                relayed = report.isRelayed(),
                                bytesReceived = report.inboundAudioBytes(),
                            )
                        }
                    }
                    delay(1_500)
                }
            }
        }

        fun close() {
            statsJob?.cancel()
            inbox.close()
            runCatching { pc.close() }
            linkStates.remove(id)
            publishLinks()
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

            // Only the impolite peer offers; anything else is a protocol error from the far side.
            if (isOffer != polite) {
                log.warn("peer {}: ignoring unexpected {}", id, signal.kind)
                return
            }

            val description = RTCSessionDescription(
                if (isOffer) RTCSdpType.OFFER else RTCSdpType.ANSWER,
                sdp,
            )

            log.debug("peer {}: applying remote {}", id, signal.kind)
            queue.withLock {
                pc.setRemoteDescription(description)
                remoteDescriptionSet = true

                pendingCandidates.forEach { runCatching { pc.addIceCandidate(it) } }
                pendingCandidates.clear()

                if (isOffer) {
                    // The offer created the audio transceiver. Attaching our microphone to it
                    // (and flipping it to sendrecv) is what makes this side audible; adding a
                    // separate transceiver beforehand would leave the answer recvonly.
                    attachLocalAudioToIncoming()
                    val answer = pc.createAnswerAwait()
                    pc.setLocalDescription(answer)
                    log.debug(
                        "peer {}: sending answer ({} chars) dirs={}",
                        id,
                        answer.sdp.length,
                        answer.sdp.lineSequence().filter { it.startsWith("a=send") || it.startsWith("a=recv") }.toList(),
                    )
                    emit(id, SignalBody(kind = "answer", sdp = answer.sdp))
                }
            }
        }

        private suspend fun applyCandidate(signal: SignalBody) {
            val sdp = signal.candidate ?: return
            val candidate = RTCIceCandidate(
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

        /** Must happen before the offer/answer so the transceiver is reused, not duplicated. */
        private fun ensureLocalAudio() {
            if (transceiver != null) return
            val init = RTCRtpTransceiverInit().apply {
                direction = RTCRtpTransceiverDirection.SEND_RECV
                streamIds = listOf("audio")
            }
            transceiver = pc.addTransceiver(localTrack, init)
        }

        private fun attachLocalAudioToIncoming() {
            val incoming = pc.transceivers.firstOrNull { candidate ->
                candidate.receiver?.track?.kind == MediaStreamTrack.AUDIO_TRACK_KIND
            } ?: pc.transceivers.firstOrNull() ?: return

            transceiver = incoming
            receiver = incoming.receiver
            runCatching { incoming.sender.replaceTrack(localTrack) }
                .onFailure { log.warn("peer {}: could not attach microphone: {}", id, it.message) }
            incoming.direction = RTCRtpTransceiverDirection.SEND_RECV
        }
    }
}

// ---------------------------------------------------------------------- callbacks

private suspend fun RTCPeerConnection.createOfferAwait(): RTCSessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(
            RTCOfferOptions(),
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    continuation.resume(description)
                }

                override fun onFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException("createOffer: $error"))
                }
            },
        )
    }

private suspend fun RTCPeerConnection.createAnswerAwait(): RTCSessionDescription =
    suspendCancellableCoroutine { continuation ->
        createAnswer(
            RTCAnswerOptions(),
            object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    continuation.resume(description)
                }

                override fun onFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException("createAnswer: $error"))
                }
            },
        )
    }

private suspend fun RTCPeerConnection.setLocalDescription(description: RTCSessionDescription): Unit =
    suspendCancellableCoroutine { continuation ->
        setLocalDescription(
            description,
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    continuation.resume(Unit)
                }

                override fun onFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException("setLocalDescription: $error"))
                }
            },
        )
    }

private suspend fun RTCPeerConnection.setRemoteDescription(description: RTCSessionDescription): Unit =
    suspendCancellableCoroutine { continuation ->
        setRemoteDescription(
            description,
            object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    continuation.resume(Unit)
                }

                override fun onFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException("setRemoteDescription: $error"))
                }
            },
        )
    }

private suspend fun RTCPeerConnection.statsAwait(): RTCStatsReport =
    suspendCancellableCoroutine { continuation ->
        getStats(
            object : RTCStatsCollectorCallback {
                override fun onStatsDelivered(report: RTCStatsReport) {
                    continuation.resume(report)
                }
            },
        )
    }

/** True when the selected ICE candidate pair goes through a TURN relay. */
private fun RTCStatsReport.isRelayed(): Boolean {    val stats = this.stats
    val pair = stats.values.firstOrNull { entry ->
        entry.type == RTCStatsType.CANDIDATE_PAIR &&
            (entry.members["nominated"] == true || entry.members["state"] == "succeeded")
    } ?: return false

    val localId = pair.members["localCandidateId"] as? String ?: return false
    val localType = stats[localId]?.members?.get("candidateType") as? String
    return localType == "relay"
}

private fun RTCStatsReport.inboundAudioBytes(): Long =
    stats.values.asSequence()
        .filter { it.type == RTCStatsType.INBOUND_RTP }
        .sumOf { entry -> (entry.members["bytesReceived"] as? Number)?.toLong() ?: 0L }

private const val MAX_PENDING_CANDIDATES = 256
