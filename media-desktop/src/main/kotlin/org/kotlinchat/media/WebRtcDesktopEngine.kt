package org.kotlinchat.media

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
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
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
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
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    override fun screenSources(): List<ScreenShareSource> = runCatching {
        val screens = ScreenCapturer()
        val windows = WindowCapturer()
        try {
            buildList {
                screens.desktopSources.forEach { source ->
                    add(ScreenShareSource(source.id, source.title.ifBlank { "Screen ${source.id}" }, isWindow = false))
                }
                windows.desktopSources.take(MAX_WINDOW_SOURCES).forEach { source ->
                    add(ScreenShareSource(source.id, source.title.ifBlank { "Window ${source.id}" }, isWindow = true))
                }
            }
        } finally {
            runCatching { screens.dispose() }
            runCatching { windows.dispose() }
        }
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
                val audioOptions = AudioOptions().apply {
                    echoCancellation = options.echoCancellation
                    noiseSuppression = options.noiseSuppression
                    autoGainControl = options.autoGainControl
                }
                val source = factory.createAudioSource(audioOptions)
                val track = factory.createAudioTrack("mic", source)

                // The screen source exists for the whole session but only captures while the
                // user is sharing. Negotiating the video m-line up front means starting and
                // stopping a share never needs renegotiation.
                val videoSource = VideoDesktopSource()
                val videoTrack = factory.createVideoTrack("screen", videoSource)

                val iceServers = options.iceServers.map { server ->
                    RTCIceServer().apply {
                        urls = server.urls
                        username = server.username
                        password = server.credential
                    }
                }
                WebRtcSession(factory, adm, track, videoSource, videoTrack, iceServers)
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
    private val videoSource: VideoDesktopSource,
    private val videoTrack: VideoTrack,
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

    private val _sharing = MutableStateFlow(false)
    override val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    private val _remoteFrames = MutableStateFlow<Map<String, VideoFrameData>>(emptyMap())
    override val remoteFrames: StateFlow<Map<String, VideoFrameData>> = _remoteFrames.asStateFlow()

    private val _dataChannels = MutableStateFlow<Map<String, PeerDataChannel>>(emptyMap())
    override val dataChannels: StateFlow<Map<String, PeerDataChannel>> = _dataChannels.asStateFlow()

    private val peers = ConcurrentHashMap<String, PeerLink>()
    private val linkStates = ConcurrentHashMap<String, PeerLinkInfo>()
    private val frameSequence = AtomicLong(0)

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

    override fun startScreenShare(source: ScreenShareSource, maxWidth: Int, maxHeight: Int, fps: Int) {
        runCatching {
            videoSource.setSourceId(source.id, source.isWindow)
            videoSource.setFrameRate(fps)
            videoSource.setMaxFrameSize(maxWidth, maxHeight)
            videoSource.start()
            _sharing.value = true
            log.info("sharing '{}' at {}x{}@{}", source.title, maxWidth, maxHeight, fps)
        }.onFailure {
            _sharing.value = false
            log.warn("could not start screen share: {}", it.message)
        }
    }

    override fun stopScreenShare() {
        runCatching { videoSource.stop() }
        _sharing.value = false
        log.info("screen share stopped")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { videoSource.stop() }
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        linkStates.clear()
        publishLinks()
        _remoteFrames.value = emptyMap()
        scope.cancel()
        runCatching { videoTrack.dispose() }
        runCatching { videoSource.dispose() }
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
        private var audioTransceiver: RTCRtpTransceiver? = null
        private var videoTransceiver: RTCRtpTransceiver? = null
        private var remoteVideoSink: VideoTrackSink? = null
        private var dataLink: DataChannelLink? = null
        private var lastPreviewNanos = 0L
        private var receiver: RTCRtpReceiver? = null
        private var statsJob: Job? = null
        private var recoveryAttempts = 0

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

                when (mapped) {
                    PeerLinkState.CONNECTED -> recoveryAttempts = 0
                    PeerLinkState.FAILED -> scheduleIceRestart()
                    else -> Unit
                }
            }

            override fun onTrack(transceiver: RTCRtpTransceiver) {
                if (transceiver.receiver?.track?.kind == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    attachRemoteVideo(transceiver)
                }
            }

            override fun onDataChannel(dataChannel: RTCDataChannel) {
                bindDataChannel(dataChannel)
            }
        }

        val pc: RTCPeerConnection = factory.createPeerConnection(configuration, observer)

        init {
            if (!polite) {
                // Created before the offer so the SCTP association is negotiated with the
                // media and file transfer never renegotiates.
                runCatching {
                    pc.createDataChannel(
                        DATA_CHANNEL_LABEL,
                        RTCDataChannelInit().apply { ordered = true },
                    )
                }.onSuccess { bindDataChannel(it) }
                    .onFailure { log.warn("peer {}: could not create data channel: {}", id, it.message) }
            }

            scope.launch {
                for (signal in inbox) {
                    runCatching { process(signal) }
                        .onFailure { log.warn("peer {}: failed to apply {}: {}", id, signal.kind, it.message) }
                }
            }
        }

        fun bindDataChannel(channel: RTCDataChannel) {
            if (dataLink != null) return
            log.info("peer {}: data channel '{}' bound, state={}", id, channel.label, channel.state)
            val link = DataChannelLink(id, channel)
            dataLink = link
            _dataChannels.update { it + (id to link) }
        }

        fun enqueue(signal: SignalBody) {
            inbox.trySend(signal)
        }

        suspend fun negotiate() {
            queue.withLock {
                makingOffer = true
                try {
                    ensureLocalMedia()
                    val offer = pc.createOfferAwait()
                    pc.setLocalDescription(offer)
                    log.debug("peer {}: sending offer ({} chars)", id, offer.sdp.length)
                    log.debug(
                        "peer {}: offer m-lines {}",
                        id,
                        offer.sdp.lineSequence().filter { it.startsWith("m=") }.toList(),
                    )
                    emit(id, SignalBody(kind = "offer", sdp = offer.sdp))
                } finally {
                    makingOffer = false
                }
            }
        }

        /**
         * Only the impolite peer drives recovery; the polite peer just answers the restart
         * offer. Backoff grows with each attempt and gives up after a few tries so a dead peer
         * does not spin forever.
         */
        private fun scheduleIceRestart() {
            if (polite || recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return
            recoveryAttempts++
            val attempt = recoveryAttempts
            scope.launch {
                delay(1_000L * attempt)
                runCatching {
                    queue.withLock {
                        if (closed.get() || !peers.containsKey(id)) return@withLock
                        log.info("peer {}: restarting ICE (attempt {})", id, attempt)
                        pc.restartIce()
                        val offer = pc.createOfferAwait(iceRestart = true)
                        pc.setLocalDescription(offer)
                        emit(id, SignalBody(kind = "offer", sdp = offer.sdp))
                    }
                }.onFailure { log.warn("peer {}: ICE restart failed: {}", id, it.message) }
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
                                inbound.joinToString { "bytes=${it.attributes["bytesReceived"]} kind=${it.attributes["kind"]}" },
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
            dataLink?.let { link ->
                _dataChannels.update { it - id }
                runCatching { link.close() }
            }
            dataLink = null
            runCatching { pc.close() }
            linkStates.remove(id)
            publishLinks()
            _remoteFrames.update { it - id }
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
                    // The offer created the transceivers. Attaching our tracks to them (and
                    // flipping them to sendrecv) is what makes this side audible and visible;
                    // adding separate transceivers beforehand would leave the answer recvonly.
                    attachLocalMediaToIncoming()
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

        /** Must happen before the offer/answer so the transceivers are reused, not duplicated. */
        private fun ensureLocalMedia() {
            val init = RTCRtpTransceiverInit().apply {
                direction = RTCRtpTransceiverDirection.SEND_RECV
                streamIds = listOf("kotlin-chat")
            }
            if (audioTransceiver == null) {
                audioTransceiver = pc.addTransceiver(localTrack, init)
            }
            if (videoTransceiver == null) {
                // Negotiated with every peer up front so sharing never needs renegotiation.
                videoTransceiver = pc.addTransceiver(videoTrack, init)
            }
        }

        private fun attachLocalMediaToIncoming() {
            pc.transceivers.forEach { incoming ->
                when (incoming.receiver?.track?.kind) {
                    MediaStreamTrack.AUDIO_TRACK_KIND -> {
                        audioTransceiver = incoming
                        receiver = incoming.receiver
                        runCatching { incoming.sender.replaceTrack(localTrack) }
                            .onFailure { log.warn("peer {}: could not attach microphone: {}", id, it.message) }
                        incoming.direction = RTCRtpTransceiverDirection.SEND_RECV
                    }

                    MediaStreamTrack.VIDEO_TRACK_KIND -> {
                        videoTransceiver = incoming
                        runCatching { incoming.sender.replaceTrack(videoTrack) }
                            .onFailure { log.warn("peer {}: could not attach screen track: {}", id, it.message) }
                        incoming.direction = RTCRtpTransceiverDirection.SEND_RECV
                        attachRemoteVideo(incoming)
                    }
                }
            }
        }

        private fun attachRemoteVideo(transceiver: RTCRtpTransceiver) {
            if (remoteVideoSink != null) return
            val track = transceiver.receiver?.track as? VideoTrack ?: return
            val sink = VideoTrackSink { frame -> publishPreview(frame) }
            remoteVideoSink = sink
            runCatching { track.addSink(sink) }
                .onFailure { log.warn("peer {}: could not attach video sink: {}", id, it.message) }
        }

        /**
         * Downscales and converts at a UI-friendly rate. Full-resolution frames are far too
         * large to push through a StateFlow every encoded frame.
         */
        private fun publishPreview(frame: VideoFrame) {
            val now = System.nanoTime()
            if (now - lastPreviewNanos < PREVIEW_INTERVAL_NANOS) return

            val buffer = frame.buffer
            val srcWidth = buffer.width
            val srcHeight = buffer.height
            if (srcWidth <= 0 || srcHeight <= 0) return

            val scale = minOf(
                1.0,
                PREVIEW_MAX_WIDTH.toDouble() / srcWidth,
                PREVIEW_MAX_HEIGHT.toDouble() / srcHeight,
            )
            val dstWidth = ((srcWidth * scale).toInt().coerceAtLeast(2)) and 1.inv()
            val dstHeight = ((srcHeight * scale).toInt().coerceAtLeast(2)) and 1.inv()

            val scaled = if (dstWidth == srcWidth && dstHeight == srcHeight) {
                buffer
            } else {
                runCatching { buffer.cropAndScale(0, 0, srcWidth, srcHeight, dstWidth, dstHeight) }
                    .getOrNull() ?: return
            }

            try {
                val rgba = ByteArray(dstWidth * dstHeight * 4)
                VideoBufferConverter.convertFromI420(scaled, rgba, FourCC.RGBA)
                lastPreviewNanos = now
                _remoteFrames.update { frames ->
                    frames + (id to VideoFrameData(dstWidth, dstHeight, rgba, frameSequence.incrementAndGet()))
                }
            } catch (t: Throwable) {
                log.debug("peer {}: frame conversion failed: {}", id, t.message)
            } finally {
                if (scaled !== buffer) runCatching { scaled.release() }
            }
        }
    }
}

// ---------------------------------------------------------------------- callbacks

private suspend fun RTCPeerConnection.createOfferAwait(iceRestart: Boolean = false): RTCSessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(
            RTCOfferOptions().apply { this.iceRestart = iceRestart },
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
            (entry.attributes["nominated"] == true || entry.attributes["state"] == "succeeded")
    } ?: return false

    val localId = pair.attributes["localCandidateId"] as? String ?: return false
    val localType = stats[localId]?.attributes?.get("candidateType") as? String
    return localType == "relay"
}

private fun RTCStatsReport.inboundAudioBytes(): Long =
    stats.values.asSequence()
        .filter { it.type == RTCStatsType.INBOUND_RTP }
        .sumOf { entry -> (entry.attributes["bytesReceived"] as? Number)?.toLong() ?: 0L }

private const val MAX_PENDING_CANDIDATES = 256
private const val MAX_RECOVERY_ATTEMPTS = 3
private const val MAX_WINDOW_SOURCES = 60
private const val PREVIEW_MAX_WIDTH = 640
private const val PREVIEW_MAX_HEIGHT = 360
private const val PREVIEW_INTERVAL_NANOS = 200_000_000L // 5 fps is plenty for slides
private const val DATA_CHANNEL_LABEL = "files"
private const val HIGH_WATER_BYTES = 4L * 1024 * 1024
private const val OPEN_WAIT_SPINS = 100
private const val OPEN_WAIT_MS = 100L
private const val BACKPRESSURE_SPINS = 600

/** Reliable, ordered channel carrying raw file chunks. */
private class DataChannelLink(
    override val peerId: String,
    private val channel: RTCDataChannel,
) : PeerDataChannel {

    private val log = LoggerFactory.getLogger(DataChannelLink::class.java)
    private val incomingChannel = Channel<ByteArray>(capacity = 64)
    override val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()
    private val bufferedSignal = Channel<Unit>(Channel.CONFLATED)

    private val _open = MutableStateFlow(false)
    override val open: StateFlow<Boolean> = _open.asStateFlow()

    init {
        _open.value = currentState() == RTCDataChannelState.OPEN
        channel.registerObserver(
            object : RTCDataChannelObserver {
                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    val data = buffer.data
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    incomingChannel.trySend(bytes)
                }

                override fun onStateChange() {
                    val state = currentState()
                    log.info("peer {}: data channel state {}", peerId, state)
                    _open.value = state == RTCDataChannelState.OPEN
                    if (state == RTCDataChannelState.CLOSED) incomingChannel.close()
                }

                override fun onBufferedAmountChange(sentDataSize: Long) {
                    bufferedSignal.trySend(Unit)
                }
            },
        )
    }

    private fun currentState(): RTCDataChannelState? =
        runCatching { channel.state }.getOrNull()

    override suspend fun send(bytes: ByteArray): Boolean {
        var spins = 0
        while (!open.value) {
            if (spins++ > OPEN_WAIT_SPINS) return false
            delay(OPEN_WAIT_MS)
        }

        spins = 0
        while (channel.bufferedAmount > HIGH_WATER_BYTES) {
            if (spins++ > BACKPRESSURE_SPINS) return false
            withTimeoutOrNull(100) { bufferedSignal.receive() }
        }

        return runCatching {
            channel.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), true))
            true
        }.getOrDefault(false)
    }

    override fun close() {
        runCatching { channel.unregisterObserver() }
        runCatching { channel.close() }
        incomingChannel.close()
    }
}
