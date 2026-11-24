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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.kotlinchat.client.rtc.AudioDeviceList
import org.kotlinchat.client.rtc.AudioDeviceSelection
import org.kotlinchat.client.rtc.IceServerConfig
import org.kotlinchat.client.rtc.RtcEngine
import org.kotlinchat.client.rtc.RtcOptions
import org.kotlinchat.client.rtc.RtcSession
import org.kotlinchat.client.rtc.ScreenShareSource
import org.kotlinchat.client.file.FileTransferController
import org.kotlinchat.client.file.FILE_CAPTION_PREFIX
import org.kotlinchat.client.file.TransferProgress
import org.kotlinchat.client.history.ChatHistoryStore
import org.kotlinchat.client.history.platformChatHistoryStore
import org.kotlinchat.protocol.FileAcceptRelay
import org.kotlinchat.protocol.FileCancelRelay
import org.kotlinchat.protocol.FileDeclineRelay
import org.kotlinchat.protocol.FileOfferRelay
import org.kotlinchat.protocol.AuthRequest
import org.kotlinchat.protocol.Challenge
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.ChatSend
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.ErrorCode
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
import org.kotlinchat.protocol.RoomCreateRequest
import org.kotlinchat.protocol.RoomJoinRequest
import org.kotlinchat.protocol.RoomLeft
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
    private val historyStore: ChatHistoryStore,
) : AutoCloseable {

    constructor() : this(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        true,
        platformPinStore(),
        null,
        platformChatHistoryStore(),
    )

    constructor(rtcEngine: RtcEngine) :
        this(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            true,
            platformPinStore(),
            rtcEngine,
            platformChatHistoryStore(),
        )

    constructor(scope: CoroutineScope) :
        this(scope, false, platformPinStore(), null, platformChatHistoryStore())

    /** Visible for tests and for clients that pin somewhere other than the default file. */
    constructor(scope: CoroutineScope, pinStore: PinStore, rtcEngine: RtcEngine? = null) :
        this(scope, false, pinStore, rtcEngine, platformChatHistoryStore())

    /** Visible for clients that need a different history store, including tests. */
    constructor(
        scope: CoroutineScope,
        pinStore: PinStore,
        rtcEngine: RtcEngine?,
        historyStore: ChatHistoryStore,
    ) : this(scope, false, pinStore, rtcEngine, historyStore)

    private val _snapshot = MutableStateFlow(ChatSnapshot())
    val snapshot: StateFlow<ChatSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    private val fileTransfers = FileTransferController(
        scope = scope,
        send = { type, payload -> sendProtocol(type, payload) },
        memberName = { peerId -> memberName(peerId) },
        downloadDirectory = { null },
    )

    /** File transfers in flight and recently finished. */
    val transfers: StateFlow<List<TransferProgress>> get() = fileTransfers.transfers

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
    private var lastRoomId: String? = LOBBY
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
    private var microphoneVolume: Float = 1f
    @Volatile
    private var speakerVolume: Float = 1f
    @Volatile
    private var muted: Boolean = true
    @Volatile
    private var speakerMuted: Boolean = false
    /** Kept for calls started after leaving a room; refreshed by every Welcome. */
    @Volatile
    private var turnCredentials: TurnCredentials? = null

    /** Local history writes are on unless the user turned them off. */
    @Volatile
    private var historyEnabled: Boolean = true

    private val advertisedCapabilities: List<String> = buildList {
        if (rtcEngine?.canOfferVideoSlot == true) add(VIDEO_SLOT_CAPABILITY)
        // The client joins explicitly after authentication, so it can stay on the server screen
        // without a room (for example after pressing Leave, then reconnecting).
        add(NO_AUTO_ROOM_CAPABILITY)
    }

    // ------------------------------------------------------------------ public API

    /**
     * Seeds the room for the next connection from the saved preferences. A null value starts in
     * the lobby; an explicit Leave during the session still reconnects without a room.
     */
    fun setInitialRoom(roomId: String?) {
        if (session == null) lastRoomId = roomId?.takeIf { it.isNotBlank() } ?: LOBBY
    }

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
                roomless = false,
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
                roomless = false,
                self = null,
                messages = emptyList(),
            )
        }
    }

    suspend fun sendChat(text: String): Boolean {
        val ws = session ?: return false
        if (_snapshot.value.room == null) return false
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

    /** Leaves the current room but keeps the server connection open. */
    suspend fun leaveRoom(): Boolean {
        val ws = session ?: return false
        return send(ws, Envelope.of(MessageType.ROOM_LEAVE, id = nextId()))
    }

    /**
     * Asks the server for a new room and joins it. True once the room list grew, which is also
     * when the server has moved this client into the room; false if the server refused (name
     * taken, limit reached, creation disabled).
     */
    suspend fun createRoom(name: String, topic: String = "", voice: Boolean = true): Boolean {
        val ws = session ?: return false
        val before = _snapshot.value.rooms.size
        val sent = send(
            ws,
            Envelope.of(
                MessageType.ROOM_CREATE,
                id = nextId(),
                payload = payloadOf(RoomCreateRequest(name.trim(), topic.trim(), voice)),
            ),
        )
        if (!sent) return false
        return withTimeoutOrNull(5_000) {
            _snapshot.first { it.rooms.size > before }
        } != null
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

    /**
     * Silences incoming audio without touching the microphone. The room sees this as deafened;
     * unmuting restores the speaker level from the settings.
     */
    suspend fun setSpeakerMuted(muted: Boolean) {
        speakerMuted = muted
        rtc?.applyAudio(audioDevices, microphoneVolume, effectiveSpeakerVolume())
        _snapshot.update { it.copy(voice = it.voice.copy(speakerMuted = muted)) }
        sendPresence()
    }

    /** Applies on the next connection; [applyAudioSettings] is the live version. */
    fun selectAudioDevices(selection: AudioDeviceSelection) {
        audioDevices = selection
    }

    /**
     * The `mute on join` preference. Deliberately only touches an inactive call so changing the
     * setting never mutes or unmutes the microphone the user is already using.
     */
    fun setDefaultMuted(muted: Boolean) {
        if (rtc == null) this.muted = muted
    }

    /** Pushes device and volume changes into the live session, if there is one. */
    fun applyAudioSettings(
        devices: AudioDeviceSelection,
        microphoneVolume: Float,
        speakerVolume: Float,
    ) {
        this.audioDevices = devices
        this.microphoneVolume = microphoneVolume.coerceIn(0f, 1f)
        this.speakerVolume = speakerVolume.coerceIn(0f, 1f)
        rtc?.applyAudio(this.audioDevices, this.microphoneVolume, effectiveSpeakerVolume())
    }

    /** Device lists for the connect screen; empty when voice is unavailable. */
    fun inputDevices() = rtcEngine?.inputDevices().orEmpty()

    fun outputDevices() = rtcEngine?.outputDevices().orEmpty()

    /** Asynchronous inventory for the settings screen (Android enumerates in a coroutine). */
    suspend fun audioDevices(): AudioDeviceList =
        rtcEngine?.audioDevices() ?: AudioDeviceList()

    fun screenSources() = rtcEngine?.screenSources().orEmpty()

    suspend fun offerFile(path: String, caption: String = "") = fileTransfers.offerFile(path, caption)

    suspend fun acceptTransfer(id: String, peerId: String) = fileTransfers.accept(id, peerId)

    suspend fun declineTransfer(id: String, peerId: String) = fileTransfers.decline(id, peerId)

    suspend fun cancelTransfer(id: String, peerId: String) = fileTransfers.cancel(id, peerId)

    /** Where incoming files are written; null means the user's Downloads folder. */
    fun setDownloadDirectory(path: String?) = fileTransfers.setDownloadDirectory(path)

    // ------------------------------------------------------------------ history

    /**
     * Turns local message storage on or off. Turning it back on merges the room's stored history
     * into the current feed; existing files are never deleted by this switch.
     */
    fun setHistoryEnabled(enabled: Boolean) {
        val was = historyEnabled
        historyEnabled = enabled
        if (enabled && !was) scope.launch { mergeCurrentHistory() }
    }

    /** Clears this server's history for [roomId], or for every room when it is null. */
    fun clearHistory(roomId: String? = null) {
        endpoint?.let { clearHistory(it, roomId) }
    }

    /**
     * Clears history for a remembered server even while disconnected. In-memory messages are
     * dropped only when that server is the current one.
     */
    fun clearHistory(target: ServerEndpoint, roomId: String? = null) {
        scope.launch(Dispatchers.IO) {
            runCatching { historyStore.clear(target.key, roomId) }
        }
        if (target.key != endpoint?.key) return
        if (roomId == null || roomId == _snapshot.value.roomId) {
            _snapshot.update { it.copy(messages = emptyList()) }
        }
    }

    private fun historyServerKey(): String? = endpoint?.key

    private suspend fun loadHistory(roomId: String, serverKey: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            runCatching { historyStore.load(serverKey, roomId, MAX_MESSAGES) }.getOrDefault(emptyList())
        }

    private suspend fun mergeCurrentHistory() {
        val roomId = _snapshot.value.roomId ?: return
        val serverKey = historyServerKey() ?: return
        val stored = loadHistory(roomId, serverKey)
        if (stored.isEmpty()) return
        _snapshot.update { snapshot ->
            if (snapshot.roomId != roomId) {
                snapshot
            } else {
                snapshot.copy(
                    messages = (stored + snapshot.messages)
                        .distinctBy { it.messageId }
                        .sortedBy { it.ts }
                        .takeLast(MAX_MESSAGES),
                )
            }
        }
    }

    /**
     * Drops the pinned certificate for this server so the next connect trusts whatever key it
     * presents. This is the only way past a changed fingerprint, and it is deliberately manual.
     */
    fun forgetPin(endpoint: ServerEndpoint) {
        pinStore.remove(endpoint.key)
        _snapshot.update {
            it.copy(serverFingerprint = null, lastError = null, connection = ConnectionState.Idle)
        }
    }

    /**
     * Reads the server certificate without connecting, so the UI can ask the user before a key
     * is pinned. Returns null when TLS is off and there is nothing to trust.
     */
    suspend fun checkServerKey(endpoint: ServerEndpoint): ServerKeyTrust? {
        if (!endpoint.tls) return null
        val presented = probeTlsFingerprint(endpoint.host, endpoint.port)
        val stored = pinStore.get(endpoint.key)
        return when {
            stored == null -> ServerKeyTrust.FirstSight(presented)
            stored.equals(presented, ignoreCase = true) -> ServerKeyTrust.Pinned(stored)
            else -> ServerKeyTrust.Changed(pinned = stored, presented = presented)
        }
    }

    /**
     * Pins [fingerprint] after the user approved it. An existing pin is never replaced: a changed
     * key has to go through [forgetPin] so trusting a new key stays deliberate.
     */
    fun trustServerKey(endpoint: ServerEndpoint, fingerprint: String) {
        if (pinStore.get(endpoint.key) == null) {
            pinStore.put(endpoint.key, fingerprint)
        }
    }

    suspend fun startScreenShare(
        source: ScreenShareSource,
        maxWidth: Int = 1280,
        maxHeight: Int = 720,
        fps: Int = 10,
    ) {
        val voice = rtc ?: return
        val started = runCatching {
            voice.startScreenShare(source, maxWidth = maxWidth, maxHeight = maxHeight, fps = fps)
        }.isSuccess
        val sharing = started && voice.sharing.value
        _snapshot.update {
            it.copy(
                screen = it.screen.copy(
                    sharing = sharing,
                    error = if (sharing) null else "could not start screen capture",
                ),
            )
        }
        sendPresence()
    }

    suspend fun stopScreenShare() {
        val voice = rtc ?: return
        voice.stopScreenShare()
        _snapshot.update { it.copy(screen = it.screen.copy(sharing = false)) }
        sendPresence()
    }

    override fun close() {
        disconnect()
        fileTransfers.close()
        if (ownsScope) scope.cancel()
    }

    // ------------------------------------------------------------------ lifecycle

    private suspend fun runLoop(generationAtStart: Int) {
        var attempt = 0
        var cause: String? = null
        var hint: String? = null
        var detail: String? = null

        while (currentCoroutineContext().isActive &&
            desired &&
            generationAtStart == generation
        ) {
            cause = null
            hint = null
            detail = null
            val startedAt = System.currentTimeMillis()

            try {
                openSession(generationAtStart, attempt)
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                cause = "handshake timed out"
                hint = "The connection was opened but the server never finished the handshake."
            } catch (e: FatalClientException) {
                if (generationAtStart == generation) {
                    val target = endpoint ?: ServerEndpoint("", 0)
                    val failure = classifyConnectionFailure(e, target)
                    _snapshot.update {
                        it.copy(connection = ConnectionState.Failed(failure), lastError = failure.summary)
                    }
                    _events.emit(ClientEvent.Error(null, failure.summary))
                }
                desired = false
                return
            } catch (t: Throwable) {
                val failure = classifyConnectionFailure(t, endpoint ?: ServerEndpoint("", 0))
                cause = failure.summary
                hint = failure.hint
                detail = failure.detail
            }

            if (!desired || generationAtStart != generation) return

            if (System.currentTimeMillis() - startedAt > 5_000) attempt = 0
            attempt++
            val delayMillis = backoffMillis(attempt)
            if (generationAtStart == generation) {
                _snapshot.update {
                    it.copy(connection = ConnectionState.Reconnecting(attempt, delayMillis, cause, hint, detail))
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

        val http = newHttpClient(pin, target.host)
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
        val handshake = Envelope.of(
            MessageType.HELLO,
            id = nextId(),
            payload = payloadOf(Hello(name = displayName, capabilities = advertisedCapabilities)),
        )
        send(ws, handshake)
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

        val serverName = welcome.serverName.displayServerName()
        turnCredentials = welcome.turn
        _snapshot.update {
            it.copy(
                connection = ConnectionState.Ready,
                serverName = serverName,
                motd = welcome.motd,
                rooms = welcome.rooms,
                maxChatBytes = welcome.limits.maxChatBytes,
                roomless = lastRoomId == null,
                lastError = null,
            )
        }
        _events.emit(ClientEvent.Notice("connected to $serverName"))

        // The server leaves the session outside every room (the no-auto-room capability); join
        // the last room explicitly, or stay on the server screen when the user just left one.
        lastRoomId?.let { roomId ->
            send(
                ws,
                Envelope.of(
                    MessageType.ROOM_JOIN,
                    id = nextId(),
                    payload = payloadOf(RoomJoinRequest(roomId)),
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
            MessageType.ROOM_LEFT -> {
                val left = runCatching { envelope.decodePayload<RoomLeft>() }.getOrNull()
                lastRoomId = null
                stopVoice()
                _snapshot.update { snapshot ->
                    snapshot.copy(
                        room = null,
                        self = null,
                        roomless = true,
                        messages = emptyList(),
                        rooms = left?.rooms ?: snapshot.rooms,
                        lastError = null,
                    )
                }
            }

            MessageType.ROOM_STATE -> {
                val state = runCatching { envelope.decodePayload<RoomState>() }.getOrNull() ?: return
                lastRoomId = state.room.id
                val roomChanged = _snapshot.value.room?.room?.id != state.room.id
                _snapshot.update { snapshot ->
                    val changed = snapshot.room?.room?.id != state.room.id
                    snapshot.copy(
                        room = state,
                        self = state.self,
                        roomless = false,
                        messages = if (changed) emptyList() else snapshot.messages,
                        rooms = snapshot.rooms.map { if (it.id == state.room.id) state.room else it },
                    )
                }
                reconcilePeers()
                pruneRemoteVideo()
                ensureVoice()
                if (roomChanged && historyEnabled) {
                    historyServerKey()?.let { serverKey ->
                        val stored = loadHistory(state.room.id, serverKey)
                        if (stored.isNotEmpty()) {
                            _snapshot.update { snapshot ->
                                if (snapshot.roomId != state.room.id) {
                                    snapshot
                                } else {
                                    snapshot.copy(
                                        messages = (stored + snapshot.messages)
                                            .distinctBy { it.messageId }
                                            .sortedBy { it.ts }
                                            .takeLast(MAX_MESSAGES),
                                    )
                                }
                            }
                        }
                    }
                }
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
                pruneRemoteVideo()
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
                pruneRemoteVideo()
            }

            MessageType.ROOM_LIST_RESULT -> {
                val result = runCatching { envelope.decodePayload<RoomListResult>() }.getOrNull() ?: return
                _snapshot.update { it.copy(rooms = result.rooms) }
            }

            MessageType.CHAT_MESSAGE -> {
                val message = runCatching { envelope.decodePayload<ChatMessage>() }.getOrNull() ?: return
                val captionTarget = message.clientMsgId
                    ?.takeIf { it.startsWith(FILE_CAPTION_PREFIX) }
                    ?.removePrefix(FILE_CAPTION_PREFIX)
                if (captionTarget != null) {
                    fileTransfers.setCaption(captionTarget, message.text)
                    return
                }
                _snapshot.update { snapshot ->
                    if (snapshot.roomId != message.roomId) snapshot
                    else snapshot.copy(messages = (snapshot.messages + message).takeLast(MAX_MESSAGES))
                }
                if (historyEnabled) {
                    historyServerKey()?.let { serverKey ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { historyStore.append(serverKey, message.roomId, message) }
                        }
                    }
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
                if (error.code == ErrorCode.ROOM_NOT_FOUND) lastRoomId = null
                _snapshot.update { snapshot ->
                    snapshot.copy(
                        lastError = error.message,
                        // A failed auto-join leaves us authenticated but outside every room, so
                        // show the server screen instead of an endless connect form.
                        roomless = snapshot.roomless ||
                            (snapshot.room == null && snapshot.connection is ConnectionState.Ready),
                    )
                }
                _events.emit(ClientEvent.Error(error.code, error.message))
            }

            MessageType.SERVER_SHUTDOWN ->
                _events.emit(ClientEvent.Notice("server is shutting down"))

            MessageType.RTC_SIGNAL -> {
                val relayed = runCatching { envelope.decodePayload<RtcSignalRelay>() }.getOrNull() ?: return
                handleSignal(relayed.from, relayed.signal)
            }

            MessageType.FILE_OFFER -> {
                val relayed = runCatching { envelope.decodePayload<FileOfferRelay>() }.getOrNull() ?: return
                fileTransfers.onOffer(relayed)
                _events.emit(ClientEvent.Notice("${memberName(relayed.from)} offers ${relayed.name}"))
            }

            MessageType.FILE_ACCEPT -> {
                val relayed = runCatching { envelope.decodePayload<FileAcceptRelay>() }.getOrNull() ?: return
                fileTransfers.onAccept(relayed)
            }

            MessageType.FILE_DECLINE -> {
                val relayed = runCatching { envelope.decodePayload<FileDeclineRelay>() }.getOrNull() ?: return
                fileTransfers.onDecline(relayed)
            }

            MessageType.FILE_CANCEL -> {
                val relayed = runCatching { envelope.decodePayload<FileCancelRelay>() }.getOrNull() ?: return
                fileTransfers.onCancel(relayed)
            }

            else -> Unit
        }
    }

    private fun memberName(peerId: String): String =
        _snapshot.value.members.firstOrNull { it.sessionId == peerId }?.name ?: "peer"

    private suspend fun sendProtocol(type: String, payload: JsonObject) {
        val ws = session ?: return
        send(ws, Envelope.of(type, id = nextId(), payload = payload))
    }

    // ------------------------------------------------------------------ voice

    /**
     * Starts the call when the client is in a room and stops it when the client leaves. Voice is
     * per-room, so a roomless server screen must not keep the microphone open.
     */
    private fun ensureVoice() {
        val ws = session ?: return
        if (_snapshot.value.room == null) {
            stopVoice()
            return
        }
        if (rtcEngine == null || voiceJob?.isActive == true) return
        startVoice(ws, turnCredentials)
    }

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
            microphoneVolume = microphoneVolume,
            speakerVolume = effectiveSpeakerVolume(),
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
            voice.applyAudio(audioDevices, microphoneVolume, effectiveSpeakerVolume())
            _snapshot.update {
                it.copy(
                    voice = it.voice.copy(
                        active = true,
                        muted = muted,
                        speakerMuted = speakerMuted,
                        hasRelay = options.iceServers.isNotEmpty(),
                    ),
                )
            }
            send(
                ws,
                Envelope.of(
                    MessageType.PRESENCE_UPDATE,
                    payload = payloadOf(PresenceUpdate(selfMute = muted, selfDeaf = speakerMuted)),
                ),
            )

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
                    launch {
                        voice.dataChannels.collect { available -> fileTransfers.bindChannels(available) }
                    }
                    launch {
                        voice.sharing.collect { sharing ->
                            _snapshot.update { it.copy(screen = it.screen.copy(sharing = sharing)) }
                        }
                    }
                    launch {
                        voice.remoteFrames.collect { frames ->
                            val members = _snapshot.value.members
                            val from = frames.keys.firstOrNull { peer ->
                                members.any { it.sessionId == peer && it.sharing }
                            } ?: frames.keys.firstOrNull()
                            _snapshot.update {
                                it.copy(
                                    screen = it.screen.copy(
                                        remote = from?.let(frames::get),
                                        remoteFrom = from,
                                    ),
                                )
                            }
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
        fileTransfers.abortActive()
        _snapshot.update { it.copy(voice = VoiceSnapshot(), screen = ScreenSnapshot()) }
    }

    private fun reconcilePeers() {
        val voice = rtc ?: return
        val room = _snapshot.value.room ?: return
        val self = room.self
        val others = room.members
            .filter { it.sessionId != self.sessionId }
            .associateBy { it.sessionId }
        val wanted = others.keys

        synchronized(voiceLock) {
            (knownPeers - wanted).forEach { peerId ->
                knownPeers.remove(peerId)
                pendingSignals.remove(peerId)
                voice.removePeer(peerId)
            }
            (wanted - knownPeers).forEach { peerId ->
                knownPeers.add(peerId)
                voice.addPeer(peerId, polite = isPolite(self, others.getValue(peerId)))
                pendingSignals.remove(peerId)?.forEach { buffered -> voice.handleSignal(peerId, buffered) }
            }
        }
    }

    /**
     * The side that can pre-create both media slots offers. When neither or both can, the
     * lexicographically larger session id is polite.
     */
    private fun isPolite(self: Member, peer: Member): Boolean {
        val selfSlot = VIDEO_SLOT_CAPABILITY in self.capabilities
        val peerSlot = VIDEO_SLOT_CAPABILITY in peer.capabilities
        return when {
            selfSlot && !peerSlot -> false
            !selfSlot && peerSlot -> true
            else -> self.sessionId > peer.sessionId
        }
    }

    private fun handleSignal(from: String, signal: SignalBody) {        val voice = rtc
        synchronized(voiceLock) {
            if (voice != null && from in knownPeers) {
                voice.handleSignal(from, signal)
            } else {
                val buffered = pendingSignals.getOrPut(from) { mutableListOf() }
                if (buffered.size < MAX_PENDING_SIGNALS) buffered.add(signal)
            }
        }
    }

    private fun pruneRemoteVideo() {
        val snapshot = _snapshot.value
        val from = snapshot.screen.remoteFrom ?: return
        val stillPresenting = snapshot.members.any { it.sessionId == from && it.sharing }
        if (!stillPresenting) {
            _snapshot.update { it.copy(screen = it.screen.copy(remote = null, remoteFrom = null)) }
        }
    }

    private fun effectiveSpeakerVolume(): Float = if (speakerMuted) 0f else speakerVolume

    private suspend fun sendPresence() {
        val ws = session ?: return
        send(
            ws,
            Envelope.of(
                MessageType.PRESENCE_UPDATE,
                payload = payloadOf(
                    PresenceUpdate(
                        selfMute = muted,
                        selfDeaf = speakerMuted,
                        sharing = rtc?.sharing?.value ?: false,
                    ),
                ),
            ),
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
        const val VIDEO_SLOT_CAPABILITY = "video-slot"
        const val NO_AUTO_ROOM_CAPABILITY = "no-auto-room"
        const val MAX_MESSAGES = 500
        const val MAX_PENDING_SIGNALS = 32
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
    }
}

/**
 * Servers initialized before the rename used the lowercase project name as their default
 * identity. Present that legacy default as the product name; custom names pass through.
 */
private fun String.displayServerName(): String =
    if (equals("kotlin-chat", ignoreCase = true)) "Kotlin Chat" else this

/** The server refused us in a way that retrying cannot fix. */
private class FatalClientException(message: String) : Exception(message)
