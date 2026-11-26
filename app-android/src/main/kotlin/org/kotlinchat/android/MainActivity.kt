package org.kotlinchat.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.client.ServerKeyTrust
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.client.settings.ConnectionMemory
import org.kotlinchat.client.settings.SavedConnection
import org.kotlinchat.media.android.AndroidRtcEngine
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.RoomSummary

class MainActivity : ComponentActivity() {

    private val client: ChatClient
        get() = (application as ChatApplication).client

    private val rtcEngine: AndroidRtcEngine
        get() = (application as ChatApplication).rtcEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }

        setContent {
            ChatApp(client, rtcEngine)
        }
    }

    private companion object {
        const val REQUEST_MIC = 7
    }
}

@Composable
private fun ChatApp(client: ChatClient, rtcEngine: AndroidRtcEngine) {
    val snapshot by client.snapshot.collectAsState()
    val transfers by client.transfers.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memory = remember { ConnectionMemory() }
    var saved by remember { mutableStateOf(memory.load()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var audioDevices by remember { mutableStateOf(org.kotlinchat.client.rtc.AudioDeviceList()) }

    val updates = remember(context, scope) {
        AndroidUpdateController(context, scope, BuildConfig.VERSION_NAME)
    }
    val updateState by updates.state.collectAsState()
    DisposableEffect(updates) { onDispose { updates.close() } }

    // One check per launch, and only when the user left it enabled.
    LaunchedEffect(Unit) {
        if (saved.settings.checkForUpdates) updates.check(saved.settings.skippedVersion)
    }

    fun savePreferences(transform: (ClientSettings) -> ClientSettings) {
        saved = memory.updateSettings(transform)
    }

    LaunchedEffect(Unit) {
        audioDevices = client.audioDevices()
    }
    LaunchedEffect(saved.settings.downloadDirectory) {
        client.setDownloadDirectory(saved.settings.downloadDirectory)
    }
    LaunchedEffect(saved.settings.keepHistory) {
        client.setHistoryEnabled(saved.settings.keepHistory)
    }
    LaunchedEffect(
        saved.settings.inputDeviceId,
        saved.settings.outputDeviceId,
        saved.settings.microphoneVolume,
        saved.settings.speakerVolume,
    ) {
        client.applyAudioSettings(
            org.kotlinchat.client.rtc.AudioDeviceSelection(
                saved.settings.inputDeviceId,
                saved.settings.outputDeviceId,
            ),
            saved.settings.microphoneVolume,
            saved.settings.speakerVolume,
        )
    }

    // Remember the room across launches.
    LaunchedEffect(snapshot.roomId) {
        val roomId = snapshot.roomId
        val remembered = saved.settings.lastRoomId
        if (roomId != null && roomId != remembered) {
            savePreferences { it.copy(lastRoomId = roomId) }
        }
    }

    // Open new incoming files when "open when finished" is on; never re-open old ones.
    val seenTransfers = remember {
        mutableMapOf<String, org.kotlinchat.client.file.TransferState>()
    }
    LaunchedEffect(transfers, saved.settings.openDownloadedFiles) {
        val open = saved.settings.openDownloadedFiles
        transfers.forEach { transfer ->
            val before = seenTransfers[transfer.key]
            if (open &&
                transfer.direction == org.kotlinchat.client.file.TransferDirection.INCOMING &&
                transfer.state == org.kotlinchat.client.file.TransferState.COMPLETED &&
                before != org.kotlinchat.client.file.TransferState.COMPLETED
            ) {
                openDownloadedFile(context, transfer)
            }
            seenTransfers[transfer.key] = transfer.state
        }
    }

    val updateBanner: @Composable () -> Unit = {
        AndroidUpdateBanner(
            state = updateState,
            onDownload = updates::download,
            onInstall = { updates.install() },
            onLater = updates::dismiss,
            onSkip = {
                val version = (updateState as? AndroidUpdateState.Available)?.info?.version
                if (version != null) savePreferences { it.copy(skippedVersion = version) }
                updates.dismiss()
            },
            onOpenPage = {
                updates.openReleasePage((updateState as? AndroidUpdateState.Failed)?.info?.notesUrl)
            },
        )
    }

    // Set when Connect is pressed and written once the connection is actually up, so a typo in
    // the address is never remembered. Lives here because the connect screen disappears when
    // the connection succeeds.
    var pendingSave by remember { mutableStateOf<ConnectionRequest?>(null) }

    LaunchedEffect(snapshot.connection) {
        if (snapshot.connection is ConnectionState.Ready) {
            pendingSave?.let { request ->
                memory.save(request.toSettings(saved.settings), request.password)
                pendingSave = null
                saved = memory.load()
            }
        }
    }

    // The foreground service exists for the call itself, not for merely being in a room.
    LaunchedEffect(snapshot.voice.joined, snapshot.connection, snapshot.roomId) {
        when {
            snapshot.voice.joined &&
                snapshot.connection is ConnectionState.Ready &&
                snapshot.roomId != null -> CallService.start(context)

            else -> CallService.stop(context)
        }
    }

    // One shot per launch: only when asked for, a password is stored, and the key is pinned.
    LaunchedEffect(Unit) {
        val remembered = saved
        val password = remembered.password
        if (!remembered.settings.canAutoConnect || password == null) return@LaunchedEffect
        if (snapshot.room != null || snapshot.connection !is ConnectionState.Idle) return@LaunchedEffect

        val endpoint =
            ServerEndpoint(remembered.settings.host, remembered.settings.port, remembered.settings.tls)
        // A failed probe (server down, network) is not a reason to skip: the connect loop
        // retries and reports its own reason. An unknown or changed key still waits for the user.
        val trust = runCatching { client.checkServerKey(endpoint) }.getOrNull()
        if (trust == null || trust is ServerKeyTrust.Pinned) {
            client.setInitialRoom(remembered.settings.lastRoomId)
            client.connect(endpoint, remembered.settings.name.ifBlank { "phone" }, password)
        }
    }

    // Android 15+ draws edge to edge unconditionally, so the software keyboard only shrinks the
    // content when its inset is consumed. safeDrawing includes the system bars, the cutout and
    // the IME, which keeps the message row (and the Send button) above the keyboard.
    KotlinChatTheme(saved.settings) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
            if (settingsOpen) {
                SettingsScreen(
                    settings = saved.settings,
                    snapshot = snapshot,
                    hasSavedPassword = saved.password != null,
                    canRememberPassword = memory.canRememberPassword,
                    version = BuildConfig.VERSION_NAME,
                    audioDevices = audioDevices,
                    currentRoomId = snapshot.roomId,
                    onRefreshDevices = { scope.launch { audioDevices = client.audioDevices() } },
                    onSettingsChange = { updated -> saved = memory.updateSettings { updated } },
                    onAudioPreview = { microphone, speaker ->
                        client.applyAudioSettings(
                            org.kotlinchat.client.rtc.AudioDeviceSelection(
                                saved.settings.inputDeviceId,
                                saved.settings.outputDeviceId,
                            ),
                            microphone,
                            speaker,
                        )
                    },
                    onForgetServer = {
                        memory.clear()
                        saved = SavedConnection()
                        client.disconnect()
                        settingsOpen = false
                    },
                    onForgetPin = {
                        client.forgetPin(
                            ServerEndpoint(
                                saved.settings.host,
                                saved.settings.port,
                                saved.settings.tls,
                            ),
                        )
                    },
                    onChangeServer = {
                        client.disconnect()
                        settingsOpen = false
                    },
                    onReconnect = {
                        val password = saved.password
                        if (password == null) {
                            client.disconnect()
                        } else {
                            client.setInitialRoom(saved.settings.lastRoomId)
                            client.connect(
                                ServerEndpoint(
                                    saved.settings.host,
                                    saved.settings.port,
                                    saved.settings.tls,
                                ),
                                saved.settings.name.ifBlank { "phone" },
                                password,
                            )
                        }
                        settingsOpen = false
                    },
                    onCheckUpdates = updates::checkNow,
                    onOpenReleasePage = {
                        updates.openReleasePage((updateState as? AndroidUpdateState.Failed)?.info?.notesUrl)
                    },
                    onClearHistory = { roomId ->
                        client.clearHistory(
                            ServerEndpoint(saved.settings.host, saved.settings.port, saved.settings.tls),
                            roomId,
                        )
                    },
                    onClose = { settingsOpen = false },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    updateBanner()
                    Box(Modifier.weight(1f)) {
                        if (snapshot.room == null) {
                            if (snapshot.connection is ConnectionState.Ready) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (snapshot.roomless) {
                                            "No rooms on this server."
                                        } else {
                                            "Joining a room…"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            } else {
                                ConnectScreen(
                                    client = client,
                                    initial = saved,
                                    canRememberPassword = memory.canRememberPassword,
                                    checkForUpdates = saved.settings.checkForUpdates,
                                    onCheckForUpdatesChange = { enabled ->
                                        savePreferences { it.copy(checkForUpdates = enabled) }
                                    },
                                    onStart = { request ->
                                        pendingSave = request
                                        client.setInitialRoom(
                                            request.toSettings(saved.settings).lastRoomId,
                                        )
                                        client.connect(request.endpoint, request.name, request.password)
                                    },
                                    onForgetSaved = {
                                        memory.clear()
                                        saved = SavedConnection()
                                    },
                                    onOpenSettings = { settingsOpen = true },
                                )
                            }
                        } else {
                            RoomScreen(
                                client,
                                snapshot,
                                settings = saved.settings,
                                rtcEngine = rtcEngine,
                                onOpenSettings = { settingsOpen = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(
    client: ChatClient,
    initial: SavedConnection,
    canRememberPassword: Boolean,
    checkForUpdates: Boolean,
    onCheckForUpdatesChange: (Boolean) -> Unit,
    onStart: (ConnectionRequest) -> Unit,
    onForgetSaved: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var host by remember { mutableStateOf(initial.settings.host) }
    var port by remember { mutableStateOf(initial.settings.port.toString()) }
    var tls by remember { mutableStateOf(initial.settings.tls) }
    var name by remember { mutableStateOf(initial.settings.name.ifBlank { "phone" }) }
    var password by remember { mutableStateOf(initial.password.orEmpty()) }
    var remember by remember { mutableStateOf(initial.settings.rememberPassword) }
    var autoConnect by remember { mutableStateOf(initial.settings.autoConnect) }
    var pendingTrust by remember { mutableStateOf<PendingServerTrust?>(null) }
    var checkingKey by remember { mutableStateOf(false) }
    val snapshot by client.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    fun start(request: ConnectionRequest) = onStart(request)

    // Ask about an unknown key before anything is pinned; the probe also turns a changed
    // certificate into a clear question instead of a late failure.
    suspend fun beginConnect() {
        val request = ConnectionRequest(
            endpoint = ServerEndpoint(host = host, port = port.toIntOrNull() ?: 8443, tls = tls),
            name = name.ifBlank { "phone" },
            password = password,
            rememberPassword = remember,
            autoConnect = autoConnect,
        )
        checkingKey = true
        val trust = runCatching { client.checkServerKey(request.endpoint) }.getOrNull()
        checkingKey = false
        if (trust == null || trust is ServerKeyTrust.Pinned) {
            start(request)
        } else {
            pendingTrust = PendingServerTrust(request, trust)
        }
    }

    val pending = pendingTrust
    if (pending != null) {
        TrustDialog(
            pending = pending,
            onConfirm = {
                if (pending.trust is ServerKeyTrust.Changed) client.forgetPin(pending.request.endpoint)
                client.trustServerKey(pending.request.endpoint, pending.trust.approvedFingerprint)
                pendingTrust = null
                start(pending.request)
            },
            onDismiss = { pendingTrust = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenSettings) {
                Icon(MdiIcons.cog, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Settings")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Kotlin Chat", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Voice and text over your own server.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text("server") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { tls = !tls }, modifier = Modifier.height(56.dp)) {
                Text(if (tls) "TLS on" else "TLS off")
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(32) },
            label = { Text("display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("server password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = remember && canRememberPassword,
                onCheckedChange = {
                    remember = it
                    if (!it) autoConnect = false
                },
                enabled = canRememberPassword,
            )
            Column {
                Text("remember password", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (canRememberPassword) {
                        "kept in the Android keystore, not in the settings file"
                    } else {
                        "no keystore available, so the password cannot be saved safely"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = autoConnect && remember,
                onCheckedChange = { autoConnect = it },
                enabled = remember,
            )
            Text("connect automatically on start", style = MaterialTheme.typography.bodySmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checkForUpdates,
                onCheckedChange = onCheckForUpdatesChange,
            )
            Text("check for updates at startup", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = { scope.launch { beginConnect() } },
            enabled = host.isNotBlank() && password.isNotEmpty() && !checkingKey,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (checkingKey) "Checking server key…" else "Connect") }

        val failure = (snapshot.connection as? ConnectionState.Failed)?.failure
        if (failure != null) {
            Text(failure.summary, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            failure.hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            failure.detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(
                onClick = {
                    client.forgetPin(ServerEndpoint(host, port.toIntOrNull() ?: 8443, tls))
                    // Ask about the replacement key instead of pinning it behind the user's back.
                    scope.launch { beginConnect() }
                },
            ) {
                Text("Forget server key and retry")
            }
        } else {
            val state = snapshot.connection
            if (state is ConnectionState.Reconnecting) {
                Text(state.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                state.hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state !is ConnectionState.Idle) {
                Text(state.label, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (initial.settings != ClientSettings() || initial.password != null) {
            TextButton(
                onClick = {
                    onForgetSaved()
                    host = ClientSettings().host
                    port = ClientSettings().port.toString()
                    tls = ClientSettings().tls
                    name = "phone"
                    password = ""
                    remember = false
                    autoConnect = false
                },
            ) {
                Text("Forget saved connection")
            }
        }
    }
}

@Composable
private fun RoomScreen(
    client: ChatClient,
    snapshot: ChatSnapshot,
    settings: ClientSettings,
    rtcEngine: AndroidRtcEngine,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var pendingFile by remember { mutableStateOf<PickedFile?>(null) }
    var showCreateRoom by remember { mutableStateOf(false) }
    val transfers by client.transfers.collectAsState()
    val remoteVideos by rtcEngine.remoteVideos.collectAsState()
    val presenter = snapshot.members.firstOrNull {
        it.sharing && it.sessionId != snapshot.self?.sessionId
    }

    if (showCreateRoom) {
        CreateRoomDialog(
            onCreate = { name, topic, voice ->
                showCreateRoom = false
                scope.launch { client.createRoom(name, topic, voice) }
            },
            onDismiss = { showCreateRoom = false },
        )
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingFile = describePickedFile(context, uri)
    }

    Column(Modifier.fillMaxSize()) {
        RoomHeader(
            snapshot = snapshot,
            onToggleMute = { scope.launch { client.setMicrophoneMuted(!snapshot.voice.muted) } },
            onToggleSpeakerMute = { scope.launch { client.setSpeakerMuted(!snapshot.voice.speakerMuted) } },
            onOpenSettings = onOpenSettings,
            onToggleVoice = {
                scope.launch {
                    if (snapshot.voice.joined) client.leaveVoice() else client.joinVoice()
                }
            },
        )
        RoomSwitcher(
            snapshot = snapshot,
            onJoinRoom = { roomId -> scope.launch { client.joinRoom(roomId) } },
            onCreateRoom = { showCreateRoom = true },
            onDeleteRoom = { roomId -> scope.launch { client.deleteRoom(roomId) } },
        )
        MemberStrip(snapshot)
        presenter?.let { member ->
            RemoteScreenPane(
                video = remoteVideos[member.sessionId],
                sharerName = member.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .height(200.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))

        ChatFeed(
            snapshot = snapshot,
            transfers = transfers,
            onAccept = { transfer -> scope.launch { client.acceptTransfer(transfer.id, transfer.peerId) } },
            onDecline = { transfer -> scope.launch { client.declineTransfer(transfer.id, transfer.peerId) } },
            onCancel = { transfer -> scope.launch { client.cancelTransfer(transfer.id, transfer.peerId) } },
            clock24h = settings.clock24h,
            showTimestamps = settings.showTimestamps,
            groupMessages = settings.groupMessages,
            textScale = settings.textScale,
            modifier = Modifier.weight(1f),
        )

        HorizontalDivider()
        pendingFile?.let { file ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AttachFileIcon(
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        file.size?.let { bytes ->
                            Text(
                                text = humanSize(bytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { pendingFile = null }) { Text("Remove") }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                AttachFileIcon(contentDescription = "Attach file")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(snapshot.maxChatBytes) },
                placeholder = {
                    Text(if (pendingFile != null) "Add a description (optional)" else "Message")
                },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    val file = pendingFile
                    if (file != null) {
                        pendingFile = null
                        draft = ""
                        scope.launch {
                            val preview = withContext(Dispatchers.IO) {
                                createImagePreview(context, file.uri, file.name)
                            }
                            client.offerFile(file.uri, text, preview)
                        }
                    } else if (text.isNotEmpty()) {
                        draft = ""
                        scope.launch { client.sendChat(text) }
                    }
                },
                enabled = snapshot.connection is ConnectionState.Ready &&
                    (draft.isNotBlank() || pendingFile != null),
            ) { Text("Send") }
        }
    }
}


@Composable
private fun RoomHeader(
    snapshot: ChatSnapshot,
    onToggleMute: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleVoice: () -> Unit,
) {
    val room = snapshot.room?.room
    val subtitle = buildString {
        append(room?.topic.orEmpty().ifBlank { snapshot.serverName.ifBlank { "connected" } })
        snapshot.latencyMs?.let { append(" · $it ms") }
        if (snapshot.voice.joined && !snapshot.voice.hasRelay) append(" · no relay")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = room?.name ?: "Room",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (snapshot.voice.joined) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (snapshot.voice.muted) MdiIcons.microphoneOff else MdiIcons.microphone,
                    contentDescription = if (snapshot.voice.muted) "Unmute microphone" else "Mute microphone",
                    tint = if (snapshot.voice.muted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            IconButton(onClick = onToggleSpeakerMute) {
                Icon(
                    imageVector = if (snapshot.voice.speakerMuted) MdiIcons.volumeOff else MdiIcons.volumeHigh,
                    contentDescription = if (snapshot.voice.speakerMuted) "Unmute speaker" else "Mute speaker",
                    tint = if (snapshot.voice.speakerMuted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(MdiIcons.cog, contentDescription = "Settings")
        }
        IconButton(onClick = onToggleVoice) {
            Icon(
                imageVector = if (snapshot.voice.joined) MdiIcons.phoneOff else MdiIcons.phone,
                contentDescription = if (snapshot.voice.joined) "Leave voice call" else "Join voice call",
                tint = if (snapshot.voice.joined) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun NewRoomButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MdiIcons.plus,
            contentDescription = "New room",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RoomChip(
    room: RoomSummary,
    selected: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = if (canDelete) onLongClick else null,
        ),
    ) {
        Row(
            modifier = Modifier.height(32.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = room.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = if (room.cap > 0) "${room.memberCount}/${room.cap}" else "${room.memberCount}",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun RoomSwitcher(
    snapshot: ChatSnapshot,
    onJoinRoom: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onDeleteRoom: (String) -> Unit,
) {
    var roomToDelete by remember { mutableStateOf<RoomSummary?>(null) }

    roomToDelete?.let { room ->
        DeleteRoomDialog(
            room = room,
            onConfirm = {
                roomToDelete = null
                onDeleteRoom(room.id)
            },
            onDismiss = { roomToDelete = null },
        )
    }

    val rooms = remember(snapshot.rooms, snapshot.room?.room?.id) {
        val current = snapshot.room?.room
        if (current != null && snapshot.rooms.none { it.id == current.id }) {
            snapshot.rooms + current
        } else {
            snapshot.rooms
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(snapshot.roomId, rooms.size) {
        val selected = rooms.indexOfFirst { it.id == snapshot.roomId }
        if (selected >= 0) listState.animateScrollToItem(selected)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(rooms, key = { it.id }) { room ->
            val selected = room.id == snapshot.roomId
            RoomChip(
                room = room,
                selected = selected,
                canDelete = room.id in snapshot.deletableRoomIds,
                onClick = { if (!selected) onJoinRoom(room.id) },
                onLongClick = { roomToDelete = room },
            )
        }
        item {
            NewRoomButton(onClick = onCreateRoom)
        }
    }
}

@Composable
private fun MemberStrip(snapshot: ChatSnapshot) {
    if (snapshot.members.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "In this room",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${snapshot.members.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = if (snapshot.members.size == 1) {
                    Arrangement.Center
                } else {
                    Arrangement.spacedBy(14.dp)
                },
            ) {
                items(snapshot.members, key = { it.sessionId }) { member ->
                    val isSelf = member.sessionId == snapshot.self?.sessionId
                    val level = if (isSelf) {
                        snapshot.voice.localLevel
                    } else {
                        snapshot.voice.links[member.sessionId]?.level ?: 0f
                    }
                    val inVoice = if (isSelf) snapshot.voice.joined else !member.selfDeaf
                    val muted = inVoice && if (isSelf) snapshot.voice.muted else member.selfMute
                    MemberBadge(
                        member = member,
                        isSelf = isSelf,
                        level = level,
                        inVoice = inVoice,
                        muted = muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberBadge(
    member: Member,
    isSelf: Boolean,
    level: Float,
    inVoice: Boolean,
    muted: Boolean,
) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val (background, foreground) = palette[(member.name.hashCode() and Int.MAX_VALUE) % palette.size]
    val speaking = inVoice && !muted && level > 0.08f

    Column(
        modifier = Modifier.width(76.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Surface(
                shape = CircleShape,
                color = background,
                modifier = Modifier.size(46.dp).then(
                    if (speaking) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = initials(member.name),
                        style = MaterialTheme.typography.titleSmall,
                        color = foreground,
                    )
                }
            }
            if (muted) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MdiIcons.microphoneOff,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
            }
            if (inVoice) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MdiIcons.phone,
                        contentDescription = "In the voice call",
                        modifier = Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (member.sharing) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (isSelf) "${member.name} (you)" else member.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                member.sharing -> "sharing"
                speaking -> "speaking"
                muted -> "muted"
                inVoice -> "in call"
                else -> " "
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (member.sharing || speaking || inVoice) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifBlank { "?" }
