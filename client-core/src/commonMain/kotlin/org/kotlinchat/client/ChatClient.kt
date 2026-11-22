package org.kotlinchat.client

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.kotlinchat.client.rtc.AudioDeviceSelection
import org.kotlinchat.client.rtc.IceServerConfig
import org.kotlinchat.client.rtc.RtcEngine
import org.kotlinchat.client.rtc.RtcOptions
import org.kotlinchat.client.rtc.RtcSession
import org.kotlinchat.protocol.AuthRequest
import org.kotlinchat.protocol.Challenge
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.ChatSend
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.ErrorPayload
import org.kotlinchat.protocol.Hello
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.MemberJoined
import org.kotlinchat.protocol.MemberLeft
import org.kotlinchat.protocol.MemberUpdate
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.Ping
import org.kotlinchat.protocol.Pong
import org.kotlinchat.protocol.PresenceUpdate
import org.kotlinchat.protocol.RoomJoinRequest
import org.kotlinchat.protocol.RoomListResult
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.RtcSignal
import org.kotlinchat.protocol.RtcSignalRelay
import org.kotlinchat.protocol.SignalBody
import org.kotlinchat.protocol.TurnCredentials
import org.kotlinchat.protocol.Welcome
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.Scram
import org.kotlinchat.protocol.decodePayload
import org.kotlinchat.protocol.payloadOf
import kotlin.random.Random

/**
 * Owns one logical connection: handshake, session, reconnect.
 *
 * Everything the UI needs is published through [snapshot]; [events] carries one-off notices.
 * Reconnect is a fresh session by design: new server-side session id, new room state, no
 * message replay.
 */
class ChatClient private constructor(
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
    private val pinStore: PinStore,
    private val rtcEngine: RtcEngine?,
) : AutoCloseable {

    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.Default), true, platformPinStore(), null)

    constructor(rtcEngine: RtcEngine) :
        this(CoroutineScope(SupervisorJob() + Dispatchers.Default), true, platformPinStore(), rtcEngine)

    constructor(scope: CoroutineScope) : this(scope, false, platformPinStore(), null)

    /** Visible for tests and for clients that pin somewhere other than the default file. */
    constructor(scope: CoroutineScope, pinStore: PinStore, rtcEngine: RtcEngine? = null) :
        this(scope, false, pinStore, rtcEngine)

    private val _snapshot = MutableStateFlow(ChatSnapshot())
    val snapshot: StateFlow<ChatSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    private var desired: Boolean = false

    @Volatile
    private var generation: Int = 0

    private var loopJob: Job? = null
    private var endpoint: ServerEndpoint? = null
    private var displayName: String = ""
    private var password: String = ""
    private var lastRoomId: String = LOBBY
    private var sequence: Long = 0

    // Voice state is touched from the session coroutine, the voice job and the UI.
    private val voiceLock = Any()
    @Volatile
    private var rtc: RtcSession? = null
    private var voiceJob: Job? = null
    private val knownPeers = mutableSetOf<String>()
    private val pendingSignals = mutableMapOf<String, MutableList<SignalBody>>()
    private var audioDevices = AudioDeviceSelection()
    @Volatile
    private var muted: Boolean = true

    // ------------------------------------------------------------------ public API

    fun connect(endpoint: ServerEndpoint, name: String, password: String) {
        require(name.isNotBlank()) { "display name is required" }

        this.endpoint = endpoint
        this.displayName = name.trim()
        this.password = password

        desired = true
        generation++
        val currentGeneration = generation

        loopJob?.cancel()
        _snapshot.update {
            it.copy(
                connection = ConnectionState.Connecting(endpoint.label),
                lastError = null,
            )
        }
        loopJob = scope.launch { runLoop(currentGeneration) }
    }

    fun disconnect() {
        desired = false
        generation++
        loopJob?.cancel()
        loopJob = null
        stopVoice()
        session?.let { runCatching { it.cancel() } }
        session = null
        _snapshot.update {
            it.copy(
                connection = ConnectionState.Idle,
                latencyMs = null,
                room = null,
                self = null,
                messages = emptyList(),
            )
        }
    }

    suspend fun sendChat(text: String): Boolean {
        val ws = session ?: return false
        val body = text.trim()
        if (body.isEmpty()) return false
        return send(
            ws,
            Envelope.of(
                MessageType.CHAT_SEND,
                id = nextId(),
                payload = payloadOf(ChatSend(text = body, clientMsgId = nextId())),
            ),
        )
    }

    suspend fun joinRoom(roomId: String): Boolean {
        val ws = session ?: return false
        return send(
            ws,
            Envelope.of(MessageType.ROOM_JOIN, id = nextId(), payload = payloadOf(RoomJoinRequest(roomId))),
        )
    }

    suspend fun setPresence(
        selfMute: Boolean? = null,
        selfDeaf: Boolean? = null,
        sharing: Boolean? = null,
    ): Boolean {
        val ws = session ?: return false
        return send(
            ws,
            Envelope.of(
                MessageType.PRESENCE_UPDATE,
                payload = payloadOf(PresenceUpdate(selfMute = selfMute, selfDeaf = selfDeaf, sharing = sharing)),
            ),
        )
    }

    /**
     * Turns the microphone on or off and tells the room. Voice starts muted by default so a
     * join never opens a hot mic.
     */
    suspend fun setMicrophoneMuted(muted: Boolean) {
        this.muted = muted
        rtc?.setMuted(muted)
        _snapshot.update { it.copy(voice = it.voice.copy(muted = muted)) }
        sendPresence()
    }

    /** Applies on the next connection; changing devices mid-call would restart the microphone. */
    fun selectAudioDevices(selection: AudioDeviceSelection) {
        audioDevices = selection
    }

    /** Device lists for the connect screen; empty when voice is unavailable. */
    fun inputDevices() = rtcEngine?.inputDevices().orEmpty()

    fun outputDevices() = rtcEngine?.outputDevices().orEmpty()

    override fun close() {
        disconnect()
        if (ownsScope) scope.cancel()
    }

    // ------------------------------------------------------------------ lifecycle

    private suspend fun runLoop(generationAtStart: Int) {
        var attempt = 0
        var cause: String? = null

        while (currentCoroutineContext().isActive &&
            desired &&
            generationAtStart == generation
        ) {
            cause = null
            val startedAt = System.currentTimeMillis()

            try {
                openSession(generationAtStart, attempt)
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                cause = "handshake timed out"
            } catch (e: FatalClientException) {
                if (generationAtStart == generation) {
                    val reason = e.message ?: "connection refused"
                    _snapshot.update {
                        it.copy(connection = ConnectionState.Failed(reason), lastError = reason)
                    }
                    _events.emit(ClientEvent.Error(null, reason))
                }
                desired = false
                return
            } catch (t: Throwable) {
                cause = t.message ?: t::class.simpleName ?: "connection lost"
            }

            if (!desired || generationAtStart != generation) return

            if (System.currentTimeMillis() - startedAt > 5_000) attempt = 0
            attempt++
            val delayMillis = backoffMillis(attempt)
            if (generationAtStart == generation) {
                _snapshot.update {
                    it.copy(connection = ConnectionState.Reconnecting(attempt, delayMillis, cause))
                }
            }
            delay(delayMillis)
        }
    }

    private suspend fun openSession(generationAtStart: Int, attempt: Int) {
        val target = endpoint ?: throw FatalClientException("no endpoint configured")
        val pin = resolvePin(target)

        if (generationAtStart == generation) {
            _snapshot.update {
                it.copy(
                    connection = if (attempt == 0) ConnectionState.Connecting(target.label) else it.connection,
                    serverFingerprint = pin ?: it.serverFingerprint,
                )
            }
        }

        val http = newHttpClient(pin)
        try {
            val ws = withTimeout(HANDSHAKE_TIMEOUT_MS) { http.webSocketSession(target.url) }
            session = ws
            try {
                withTimeout(HANDSHAKE_TIMEOUT_MS) { handshake(ws, generationAtStart) }
                val heartbeat = scope.launch { heartbeatLoop(ws) }
                try {
                    pump(ws, generationAtStart)
                } finally {
                    heartbeat.cancel()
                }
            } finally {
                session = null
                stopVoice()
                runCatching { ws.cancel() }
            }
        } finally {
            http.close()
        }
    }

    // ------------------------------------------------------------------ handshake

    private suspend fun handshake(ws: DefaultClientWebSocketSession, generationAtStart: Int) {
        send(
            ws,
            Envelope.of(MessageType.HELLO, id = nextId(), payload = payloadOf(Hello(name = displayName))),
        )
        if (generationAtStart == generation) {
            _snapshot.update { it.copy(connection = ConnectionState.Authenticating) }
        }

        val challenge = ws.await(MessageType.CHALLENGE).decodePayload<Challenge>()
        val serverNonce = Crypto.base64Decode(challenge.nonce)

        val keys = Scram.keys(
            withContext(Dispatchers.Default) { Scram.deriveAuthKey(password, challenge.kdf) },
        )
        val clientNonce = Crypto.randomBytes(16)
        val authMessage = Scram.authMessage(displayName, serverNonce, clientNonce)

        send(
            ws,
            Envelope.of(
                MessageType.AUTH,
                id = nextId(),
                payload = payloadOf(
                    AuthRequest(
                        clientNonce = Crypto.base64Encode(clientNonce),
                        proof = Crypto.base64Encode(Scram.clientProof(keys, authMessage)),
                    ),
                ),
            ),
        )

        val welcome = ws.await(MessageType.WELCOME).decodePayload<Welcome>()
        val signature = welcome.serverSignature
            ?.let { runCatching { Crypto.base64Decode(it) }.getOrNull() }
            ?: throw FatalClientException("server did not prove knowledge of the password")

        if (!Crypto.constantTimeEquals(signature, Scram.serverSignature(keys.serverKey, authMessage))) {
            throw FatalClientException("server signature mismatch - the endpoint may be impersonated")
        }

        _snapshot.update {
            it.copy(
                connection = ConnectionState.Ready,
                serverName = welcome.serverName,
                motd = welcome.motd,
                rooms = welcome.rooms,
                maxChatBytes = welcome.limits.maxChatBytes,
                lastError = null,
            )
        }
        _events.emit(ClientEvent.Notice("connected to ${welcome.serverName}"))

        if (rtcEngine != null) {
            startVoice(ws, welcome.turn)
        }

        if (lastRoomId != LOBBY) {
            send(
                ws,
                Envelope.of(
                    MessageType.ROOM_JOIN,
                    id = nextId(),
                    payload = payloadOf(RoomJoinRequest(lastRoomId)),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ session

    private suspend fun pump(ws: DefaultClientWebSocketSession, generationAtStart: Int) {
        for (frame in ws.incoming) {
            if (frame !is Frame.Text) continue
            if (generationAtStart != generation) return
            val envelope = runCatching { Envelope.decode(frame.readText()) }.getOrNull() ?: continue
            handle(envelope)
        }
    }

    private suspend fun handle(envelope: Envelope) {
        when (envelope.type) {
            MessageType.ROOM_STATE -> {
                val state = runCatching { envelope.decodePayload<RoomState>() }.getOrNull() ?: return
                lastRoomId = state.room.id
                _snapshot.update { snapshot ->
                    val roomChanged = snapshot.room?.room?.id != state.room.id
                    snapshot.copy(
                        room = state,
                        self = state.self,
                        messages = if (roomChanged) emptyList() else snapshot.messages,
                        rooms = snapshot.rooms.map { if (it.id == state.room.id) state.room else it },
                    )
                }
                reconcilePeers()
            }

            MessageType.MEMBER_JOINED -> {
                val joined = runCatching { envelope.decodePayload<MemberJoined>() }.getOrNull() ?: return
                _snapshot.update { snapshot ->
                    val room = snapshot.room ?: return@update snapshot
                    if (room.members.any { it.sessionId == joined.member.sessionId }) return@update snapshot
                    snapshot.copy(
                        room = room.copy(
                            members = (room.members + joined.member).sortedBy { it.joinedAt },
                        ),
                        rooms = snapshot.rooms.map {
                            if (it.id == room.room.id) it.copy(memberCount = it.memberCount + 1) else it
                        },
                    )
                }
                reconcilePeers()
            }

            MessageType.MEMBER_LEFT -> {
                val left = runCatching { envelope.decodePayload<MemberLeft>() }.getOrNull() ?: return
                _snapshot.update { snapshot ->
                    val room = snapshot.room ?: return@update snapshot
                    snapshot.copy(
                        room = room.copy(members = room.members.filterNot { it.sessionId == left.sessionId }),
                        rooms = snapshot.rooms.map {
                            if (it.id == room.room.id) {
                                it.copy(memberCount = maxOf(0, it.memberCount - 1))
                            } else {
                                it
                            }
                        },
                    )
                }
                reconcilePeers()
            }

            MessageType.MEMBER_UPDATED -> {
                val update = runCatching { envelope.decodePayload<MemberUpdate>() }.getOrNull() ?: return
                _snapshot.update { snapshot ->
                    val room = snapshot.room ?: return@update snapshot
                    snapshot.copy(
                        room = room.copy(
                            members = room.members.map { member ->
                                if (member.sessionId == update.sessionId) member.withPresence(update) else member
                            },
                        ),
                        self = snapshot.self?.takeIf { it.sessionId == update.sessionId }
                            ?.withPresence(update)
                            ?: snapshot.self,
                    )
                }
            }

            MessageType.ROOM_LIST_RESULT -> {
                val result = runCatching { envelope.decodePayload<RoomListResult>() }.getOrNull() ?: return
                _snapshot.update { it.copy(rooms = result.rooms) }
            }

            MessageType.CHAT_MESSAGE -> {
                val message = runCatching { envelope.decodePayload<ChatMessage>() }.getOrNull() ?: return
                _snapshot.update { snapshot ->
                    if (snapshot.roomId != message.roomId) snapshot
                    else snapshot.copy(messages = (snapshot.messages + message).takeLast(MAX_MESSAGES))
                }
            }

            MessageType.PONG -> {
                val pong = runCatching { envelope.decodePayload<Pong>() }.getOrNull() ?: return
                _snapshot.update {
                    it.copy(latencyMs = (System.currentTimeMillis() - pong.ts).coerceAtLeast(0))
                }
            }

            MessageType.ERROR -> {
                val error = runCatching { envelope.decodePayload<ErrorPayload>() }.getOrNull() ?: return
                _snapshot.update { it.copy(lastError = error.message) }
                _events.emit(ClientEvent.Error(error.code, error.message))
            }

            MessageType.SERVER_SHUTDOWN ->
                _events.emit(ClientEvent.Notice("server is shutting down"))

            MessageType.RTC_SIGNAL -> {
                val relayed = runCatching { envelope.decodePayload<RtcSignalRelay>() }.getOrNull() ?: return
                handleSignal(relayed.from, relayed.signal)
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------------ voice

    private fun startVoice(ws: DefaultClientWebSocketSession, turn: TurnCredentials?) {
        stopVoice()
        val engine = rtcEngine ?: return
        if (!engine.available()) {
            _snapshot.update { it.copy(voice = VoiceSnapshot(error = "voice unavailable on this system")) }
            return
        }

        val options = RtcOptions(
            iceServers = turn?.let {
                listOf(IceServerConfig(urls = it.urls, username = it.username, credential = it.credential))
            }.orEmpty(),
            devices = audioDevices,
        )

        voiceJob = scope.launch {
            val voice = try {
                engine.start(options)
            } catch (t: Throwable) {
                _snapshot.update {
                    it.copy(voice = VoiceSnapshot(error = t.message ?: "could not open the microphone"))
                }
                return@launch
            }
            rtc = voice
            voice.setMuted(muted)
            _snapshot.update { it.copy(voice = it.voice.copy(active = true, muted = muted)) }
            send(ws, Envelope.of(MessageType.PRESENCE_UPDATE, payload = payloadOf(PresenceUpdate(selfMute = muted))))

            try {
                coroutineScope {
                    launch {
                        voice.localLevel.collect { level ->
                            _snapshot.update { it.copy(voice = it.voice.copy(localLevel = level)) }
                        }
                    }
                    launch {
                        voice.links.collect { links ->
                            _snapshot.update { it.copy(voice = it.voice.copy(links = links)) }
                        }
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        voice.outgoing.collect { out ->
                            send(
                                ws,
                                Envelope.of(
                                    MessageType.RTC_SIGNAL,
                                    payload = payloadOf(RtcSignal(to = out.to, signal = out.signal)),
                                ),
                            )
                        }
                    }
                    reconcilePeers()
                    awaitCancellation()
                }
            } finally {
                rtc = null
                runCatching { voice.close() }
                _snapshot.update { it.copy(voice = VoiceSnapshot()) }
            }
        }
    }

    private fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
        val voice = synchronized(voiceLock) {
            val current = rtc
            rtc = null
            knownPeers.clear()
            pendingSignals.clear()
            current
        }
        voice?.let { runCatching { it.close() } }
        _snapshot.update { it.copy(voice = VoiceSnapshot()) }
    }

    private fun reconcilePeers() {
        val voice = rtc ?: return
        val room = _snapshot.value.room ?: return
        val selfId = room.self.sessionId
        val wanted = room.members.asSequence().map { it.sessionId }.filter { it != selfId }.toSet()

        synchronized(voiceLock) {
            (knownPeers - wanted).forEach { peerId ->
                knownPeers.remove(peerId)
                pendingSignals.remove(peerId)
                voice.removePeer(peerId)
            }
            (wanted - knownPeers).forEach { peerId ->
                knownPeers.add(peerId)
                // Smaller id is impolite and therefore sends the first offer; both sides agree.
                voice.addPeer(peerId, polite = selfId > peerId)
                pendingSignals.remove(peerId)?.forEach { buffered -> voice.handleSignal(peerId, buffered) }
            }
        }
    }

    private fun handleSignal(from: String, signal: SignalBody) {
        val voice = rtc
        synchronized(voiceLock) {
            if (voice != null && from in knownPeers) {
                voice.handleSignal(from, signal)
            } else {
                val buffered = pendingSignals.getOrPut(from) { mutableListOf() }
                if (buffered.size < MAX_PENDING_SIGNALS) buffered.add(signal)
            }
        }
    }

    private suspend fun sendPresence() {
        val ws = session ?: return
        send(
            ws,
            Envelope.of(MessageType.PRESENCE_UPDATE, payload = payloadOf(PresenceUpdate(selfMute = muted))),
        )
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun resolvePin(target: ServerEndpoint): String? {
        if (!target.tls) return null

        // Probe every time: the extra handshake gives a precise "certificate changed"
        // failure instead of an opaque TLS error, and the real connection below still
        // verifies against the stored pin.
        val presented = probeTlsFingerprint(target.host, target.port)
        val stored = pinStore.get(target.key)
        if (stored == null) {
            pinStore.put(target.key, presented)
            return presented
        }
        if (!stored.equals(presented, ignoreCase = true)) {
            throw FatalClientException(
                "server certificate changed: pinned $stored, server presented $presented. " +
                    "Verify the new fingerprint out of band before forgetting the old pin.",
            )
        }
        return stored
    }

    private suspend fun heartbeatLoop(ws: DefaultClientWebSocketSession) {
        while (currentCoroutineContext().isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            val now = System.currentTimeMillis()
            send(ws, Envelope.of(MessageType.PING, payload = payloadOf(Ping(now))))
        }
    }

    private suspend fun send(ws: DefaultClientWebSocketSession, envelope: Envelope): Boolean =
        runCatching {
            ws.send(Frame.Text(envelope.encode()))
            true
        }.getOrDefault(false)

    private suspend fun DefaultClientWebSocketSession.await(type: String): Envelope =
        withTimeout(HANDSHAKE_TIMEOUT_MS) {
            while (true) {
                val frame = incoming.receive()
                if (frame is Frame.Close) {
                    throw FatalClientException("server closed the connection during the handshake")
                }
                if (frame !is Frame.Text) continue

                val envelope = runCatching { Envelope.decode(frame.readText()) }.getOrNull() ?: continue
                if (envelope.type == MessageType.ERROR) {
                    val error = runCatching { envelope.decodePayload<ErrorPayload>() }.getOrNull()
                    throw FatalClientException(error?.message ?: "server rejected the handshake")
                }
                if (envelope.type == type) return@withTimeout envelope
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private fun backoffMillis(attempt: Int): Long {
        val base = minOf(30_000L, 500L shl minOf(attempt - 1, 6))
        val jitter = base / 5
        return base + Random.nextLong(-jitter, jitter + 1)
    }

    private fun nextId(): String = "c-${++sequence}"

    private fun Member.withPresence(update: MemberUpdate): Member = copy(
        selfMute = update.selfMute,
        selfDeaf = update.selfDeaf,
        sharing = update.sharing,
    )

    private companion object {
        const val LOBBY = "lobby"
        const val MAX_MESSAGES = 500
        const val MAX_PENDING_SIGNALS = 32
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
    }
}

/** The server refused us in a way that retrying cannot fix. */
private class FatalClientException(message: String) : Exception(message)
