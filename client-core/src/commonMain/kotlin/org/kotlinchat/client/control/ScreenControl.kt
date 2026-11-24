package org.kotlinchat.client.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kotlinchat.client.rtc.PeerDataChannel
import org.kotlinchat.client.rtc.ScreenShareSource

/** Capability advertised by a desktop client that can inject remote input. */
const val SCREEN_CONTROL_CAPABILITY = "screen-control"

enum class ScreenControlPhase { IDLE, REQUESTING, REQUESTED, ACTIVE }

enum class ScreenControlRole { VIEWER, HOST }

/** UI-facing state of the one remote-control session, if any. */
data class ScreenControlState(
    val phase: ScreenControlPhase = ScreenControlPhase.IDLE,
    val role: ScreenControlRole? = null,
    /** Viewer: the presenter. Host: the controller. */
    val peerId: String? = null,
    val peerName: String? = null,
    /** Local wall-clock time at which the lease expires. */
    val expiresAtMillis: Long? = null,
    val error: String? = null,
)

enum class RemoteMouseButton { LEFT, MIDDLE, RIGHT }

/**
 * Host-side input injection. Implemented by the desktop app for the current OS; a null sink means
 * the client cannot host control, so it must not advertise [SCREEN_CONTROL_CAPABILITY].
 */
interface RemoteInputSink {
    /** The active share source, or null when not sharing. Only screen sources are controllable. */
    fun setSource(source: ScreenShareSource?)

    /** Coordinates are normalized 0..1 inside the shared source. */
    fun movePointer(x: Float, y: Float)

    fun mouseButton(button: RemoteMouseButton, down: Boolean)

    fun wheel(delta: Int, horizontal: Boolean)

    /** AWT key code from the viewer. */
    fun key(keyCode: Int, down: Boolean)

    fun releaseAll()
}

/**
 * Coordinates the peer-to-peer consent and input flow for screen control.
 *
 * The control messages use their own reliable data channel, so they never share a stream with
 * file chunks. Both roles use the same controller: a viewer requests and sends input, a host
 * asks the user for consent and forwards input to its [RemoteInputSink].
 */
class ScreenControlController(
    private val scope: CoroutineScope,
    private val memberName: (String) -> String,
    private val leaseMinutes: () -> Int,
    private val inputSink: RemoteInputSink?,
) {
    private val _state = MutableStateFlow(ScreenControlState())
    val state: StateFlow<ScreenControlState> = _state.asStateFlow()

    private val lock = Any()
    private val channels = mutableMapOf<String, PeerDataChannel>()
    private val channelJobs = mutableMapOf<String, Job>()
    private var shareSource: ScreenShareSource? = null
    private var leaseJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var requestTimeoutJob: Job? = null
    private var leaseEndJob: Job? = null
    @Volatile
    private var lastHeartbeatNanos = 0L
    @Volatile
    private var closed = false

    fun bindChannels(available: Map<String, PeerDataChannel>) {
        val known = synchronized(lock) { channelJobs.keys.toSet() }
        (known - available.keys).forEach { peerId ->
            synchronized(lock) {
                channelJobs.remove(peerId)?.cancel()
                channels.remove(peerId)
            }
            val current = _state.value
            if (current.peerId == peerId && current.phase != ScreenControlPhase.IDLE) {
                end(error = if (current.role == ScreenControlRole.HOST) {
                    "controller disconnected"
                } else {
                    "presenter disconnected"
                })
            }
        }
        (available.keys - known).forEach { peerId ->
            val channel = available.getValue(peerId)
            synchronized(lock) { channels[peerId] = channel }
            val job = scope.launch {
                channel.incoming.collect { bytes -> onFrame(peerId, bytes) }
            }
            synchronized(lock) { channelJobs[peerId] = job }
        }
    }

    /** Called by the host when its share starts/stops. */
    fun setShareSource(source: ScreenShareSource?) {
        synchronized(lock) { shareSource = source }
        if (source == null || source.isWindow) {
            val current = _state.value
            if (current.role == ScreenControlRole.HOST && current.phase != ScreenControlPhase.IDLE) {
                val reason = if (source == null) "screen sharing stopped" else "window shares are view-only"
                current.peerId?.let { send(it, ControlFrame.Revoke(reason)) }
                end(reason)
            }
            inputSink?.setSource(null)
        } else {
            val current = _state.value
            if (current.role == ScreenControlRole.HOST && current.phase != ScreenControlPhase.IDLE) {
                current.peerId?.let { send(it, ControlFrame.Revoke("share source changed")) }
                end("share source changed")
            }
            inputSink?.setSource(source)
        }
    }

    fun request(presenterId: String) {
        if (closed || _state.value.phase != ScreenControlPhase.IDLE) return
        val channel = synchronized(lock) { channels[presenterId] } ?: return
        val name = memberName(presenterId)
        _state.value = ScreenControlState(
            phase = ScreenControlPhase.REQUESTING,
            role = ScreenControlRole.VIEWER,
            peerId = presenterId,
            peerName = name,
        )
        scope.launch {
            val sent = runCatching { channel.send(encodeControlFrame(ControlFrame.Request)) }.getOrDefault(false)
            if (!sent) {
                synchronized(lock) {
                    if (_state.value.phase == ScreenControlPhase.REQUESTING) {
                        _state.value = ScreenControlState(error = "could not reach the presenter")
                    }
                }
            }
        }
    }

    fun cancelRequest() {
        val current = _state.value
        if (current.role != ScreenControlRole.VIEWER || current.phase != ScreenControlPhase.REQUESTING) return
        current.peerId?.let { send(it, ControlFrame.Cancel) }
        _state.value = ScreenControlState()
    }

    /** Host allows the pending request. */
    fun grant() {
        val current = _state.value
        if (current.role != ScreenControlRole.HOST || current.phase != ScreenControlPhase.REQUESTED) return
        val peerId = current.peerId ?: return
        val source = synchronized(lock) { shareSource }
        if (inputSink == null || source == null || source.isWindow) {
            denyInternal("control is not available for this share")
            return
        }
        val seconds = leaseMinutes().coerceIn(1, 120) * 60
        val expires = System.currentTimeMillis() + seconds * 1000L
        send(peerId, ControlFrame.Grant(seconds))
        requestTimeoutJob?.cancel()
        requestTimeoutJob = null
        _state.value = current.copy(
            phase = ScreenControlPhase.ACTIVE,
            expiresAtMillis = expires,
            error = null,
        )
        lastHeartbeatNanos = System.nanoTime()
        startHostLease(seconds * 1000L)
    }

    fun deny() = denyInternal("declined")

    /** Viewer gives up control; host stops it. */
    fun endControl() {
        val current = _state.value
        when (current.role) {
            ScreenControlRole.VIEWER -> {
                current.peerId?.let { send(it, ControlFrame.Revoke("released")) }
            }
            ScreenControlRole.HOST -> {
                current.peerId?.let { send(it, ControlFrame.Revoke("stopped")) }
            }
            null -> Unit
        }
        end()
    }

    fun pointerMove(x: Float, y: Float) {
        val peer = viewerPeer() ?: return
        val nx = (x.coerceIn(0f, 1f) * 65535f).toInt()
        val ny = (y.coerceIn(0f, 1f) * 65535f).toInt()
        send(peer, ControlFrame.Move(nx, ny))
    }

    fun pointerButton(button: RemoteMouseButton, down: Boolean) {
        val peer = viewerPeer() ?: return
        send(peer, ControlFrame.Button(button.ordinal, down))
    }

    fun wheel(delta: Int, horizontal: Boolean) {
        val peer = viewerPeer() ?: return
        send(peer, ControlFrame.Wheel(delta.coerceIn(-32768, 32767), horizontal))
    }

    fun key(keyCode: Int, down: Boolean) {
        val peer = viewerPeer() ?: return
        send(peer, ControlFrame.Key(keyCode.coerceIn(0, 65535), down))
    }

    fun close() {
        if (closed) return
        closed = true
        end()
        synchronized(lock) {
            channelJobs.values.forEach { it.cancel() }
            channelJobs.clear()
            channels.clear()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun viewerPeer(): String? {
        val current = _state.value
        return if (current.role == ScreenControlRole.VIEWER && current.phase == ScreenControlPhase.ACTIVE) {
            current.peerId
        } else {
            null
        }
    }

    private fun onFrame(peerId: String, bytes: ByteArray) {
        val frame = decodeControlFrame(bytes) ?: return
        val current = _state.value
        when (frame) {
            ControlFrame.Request -> onRequest(peerId)
            is ControlFrame.Grant -> onGrant(peerId, frame.leaseSeconds)
            is ControlFrame.Deny -> onDeny(peerId, frame.reason)
            is ControlFrame.Revoke -> onRevoke(peerId, frame.reason)
            ControlFrame.Cancel -> onCancel(peerId)
            ControlFrame.Heartbeat -> {
                if (current.role == ScreenControlRole.HOST &&
                    current.phase == ScreenControlPhase.ACTIVE &&
                    current.peerId == peerId
                ) {
                    lastHeartbeatNanos = System.nanoTime()
                }
            }
            is ControlFrame.Move -> onInput(peerId) {
                inputSink?.movePointer(frame.x / 65535f, frame.y / 65535f)
            }
            is ControlFrame.Button -> onInput(peerId) {
                val button = RemoteMouseButton.entries.getOrNull(frame.button) ?: return@onInput
                inputSink?.mouseButton(button, frame.down)
            }
            is ControlFrame.Wheel -> onInput(peerId) {
                inputSink?.wheel(frame.delta, frame.horizontal)
            }
            is ControlFrame.Key -> onInput(peerId) {
                inputSink?.key(frame.keyCode, frame.down)
            }
        }
    }

    private fun onRequest(peerId: String) {
        val current = _state.value
        if (inputSink == null) {
            send(peerId, ControlFrame.Deny("control is not supported on this host"))
            return
        }
        val source = synchronized(lock) { shareSource }
        if (source == null) {
            send(peerId, ControlFrame.Deny("nothing is being shared"))
            return
        }
        if (source.isWindow) {
            send(peerId, ControlFrame.Deny("window shares are view-only"))
            return
        }
        if (current.phase != ScreenControlPhase.IDLE) {
            send(peerId, ControlFrame.Deny("busy"))
            return
        }
        _state.value = ScreenControlState(
            phase = ScreenControlPhase.REQUESTED,
            role = ScreenControlRole.HOST,
            peerId = peerId,
            peerName = memberName(peerId),
        )
        requestTimeoutJob?.cancel()
        requestTimeoutJob = scope.launch {
            delay(30_000)
            synchronized(lock) {
                if (_state.value.phase == ScreenControlPhase.REQUESTED &&
                    _state.value.role == ScreenControlRole.HOST &&
                    _state.value.peerId == peerId
                ) {
                    send(peerId, ControlFrame.Deny("timed out"))
                    _state.value = ScreenControlState()
                }
            }
        }
    }

    private fun onGrant(peerId: String, leaseSeconds: Int) {
        val current = _state.value
        if (current.role != ScreenControlRole.VIEWER ||
            current.phase != ScreenControlPhase.REQUESTING ||
            current.peerId != peerId
        ) {
            return
        }
        val seconds = leaseSeconds.coerceIn(60, 120 * 60)
        _state.value = current.copy(
            phase = ScreenControlPhase.ACTIVE,
            expiresAtMillis = System.currentTimeMillis() + seconds * 1000L,
            error = null,
        )
        startViewerHeartbeat(peerId)
        leaseEndJob?.cancel()
        leaseEndJob = scope.launch {
            delay(seconds * 1000L + 2_000L)
            synchronized(lock) {
                if (_state.value.role == ScreenControlRole.VIEWER &&
                    _state.value.phase == ScreenControlPhase.ACTIVE &&
                    _state.value.peerId == peerId
                ) {
                    _state.value = ScreenControlState(error = "control session expired")
                }
            }
        }
    }

    private fun onDeny(peerId: String, reason: String) {
        val current = _state.value
        if (current.role == ScreenControlRole.VIEWER && current.peerId == peerId) {
            _state.value = ScreenControlState(error = reason.ifBlank { "request denied" })
        }
    }

    private fun onRevoke(peerId: String, reason: String) {
        val current = _state.value
        if (current.peerId == peerId && current.phase != ScreenControlPhase.IDLE) {
            end(error = reason.ifBlank { "control ended" })
        }
    }

    private fun onCancel(peerId: String) {
        val current = _state.value
        if (current.role == ScreenControlRole.HOST &&
            current.phase == ScreenControlPhase.REQUESTED &&
            current.peerId == peerId
        ) {
            requestTimeoutJob?.cancel()
            requestTimeoutJob = null
            _state.value = ScreenControlState()
        }
    }

    private inline fun onInput(peerId: String, action: () -> Unit) {
        val current = _state.value
        if (current.role != ScreenControlRole.HOST ||
            current.phase != ScreenControlPhase.ACTIVE ||
            current.peerId != peerId
        ) {
            return
        }
        action()
    }

    private fun denyInternal(reason: String) {
        val current = _state.value
        if (current.role != ScreenControlRole.HOST || current.phase != ScreenControlPhase.REQUESTED) return
        current.peerId?.let { send(it, ControlFrame.Deny(reason)) }
        requestTimeoutJob?.cancel()
        requestTimeoutJob = null
        _state.value = ScreenControlState()
    }

    private fun startHostLease(leaseMillis: Long) {
        leaseJob?.cancel()
        leaseJob = scope.launch {
            delay(leaseMillis)
            synchronized(lock) {
                val current = _state.value
                if (current.role == ScreenControlRole.HOST && current.phase == ScreenControlPhase.ACTIVE) {
                    current.peerId?.let { send(it, ControlFrame.Revoke("lease expired")) }
                    _state.value = ScreenControlState(error = "control session expired")
                    inputSink?.releaseAll()
                }
            }
        }
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val current = _state.value
                if (current.role != ScreenControlRole.HOST || current.phase != ScreenControlPhase.ACTIVE) {
                    return@launch
                }
                if (System.nanoTime() - lastHeartbeatNanos > HEARTBEAT_TIMEOUT_NANOS) {
                    current.peerId?.let { send(it, ControlFrame.Revoke("controller stopped responding")) }
                    _state.value = ScreenControlState(error = "controller stopped responding")
                    inputSink?.releaseAll()
                    return@launch
                }
            }
        }
    }

    private fun startViewerHeartbeat(peerId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val current = _state.value
                if (current.role != ScreenControlRole.VIEWER ||
                    current.phase != ScreenControlPhase.ACTIVE ||
                    current.peerId != peerId
                ) {
                    return@launch
                }
                send(peerId, ControlFrame.Heartbeat)
            }
        }
    }

    private fun send(peerId: String, frame: ControlFrame) {
        val channel = synchronized(lock) { channels[peerId] } ?: return
        val bytes = encodeControlFrame(frame)
        scope.launch { runCatching { channel.send(bytes) } }
    }

    private fun end(error: String? = null) {
        leaseJob?.cancel()
        leaseJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        requestTimeoutJob?.cancel()
        requestTimeoutJob = null
        leaseEndJob?.cancel()
        leaseEndJob = null
        inputSink?.releaseAll()
        _state.value = ScreenControlState(error = error)
    }

    private companion object {
        const val HEARTBEAT_TIMEOUT_NANOS = 10_000_000_000L
    }
}
