package org.kotlinchat.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.client.ServerKeyTrust
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.client.settings.ConnectionMemory
import org.kotlinchat.client.settings.SavedConnection

class MainActivity : ComponentActivity() {

    private val client: ChatClient
        get() = (application as ChatApplication).client

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }

        setContent {
            ChatApp(client)
        }
    }

    private companion object {
        const val REQUEST_MIC = 7
    }
}

@Composable
private fun ChatApp(client: ChatClient) {
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
    LaunchedEffect(saved.settings.muteOnJoin) {
        client.setDefaultMuted(saved.settings.muteOnJoin)
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
        when (snapshot.connection) {
            is ConnectionState.Ready -> {
                CallService.start(context)
                pendingSave?.let { request ->
                    memory.save(request.toSettings(saved.settings), request.password)
                    pendingSave = null
                    saved = memory.load()
                }
            }

            is ConnectionState.Idle, is ConnectionState.Failed -> CallService.stop(context)
            else -> Unit
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
                                    client.connect(request.endpoint, request.name, request.password)
                                },
                                onForgetSaved = {
                                    memory.clear()
                                    saved = SavedConnection()
                                },
                                onOpenSettings = { settingsOpen = true },
                            )
                        } else {
                            RoomScreen(
                                client,
                                snapshot,
                                settings = saved.settings,
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
            TextButton(onClick = onOpenSettings) { Text("⚙ Settings") }
        }
        Spacer(Modifier.height(8.dp))
        Text("kotlin-chat", style = MaterialTheme.typography.headlineMedium)
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
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var showCreateRoom by remember { mutableStateOf(false) }
    val transfers by client.transfers.collectAsState()

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
        if (uri != null) scope.launch { client.offerFile(uri.toString()) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("# ${snapshot.room?.room?.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = buildString {
                        append(snapshot.serverName)
                        snapshot.latencyMs?.let { append(" · $it ms") }
                        append(" · voice ${snapshot.voice.connectedPeers}/${snapshot.voice.links.size}")
                        if (snapshot.voice.active && !snapshot.voice.hasRelay) append(" · no relay")
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onOpenSettings) { Text("⚙") }
            OutlinedButton(onClick = { client.disconnect() }) { Text("Leave") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { scope.launch { client.setMicrophoneMuted(!snapshot.voice.muted) } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (snapshot.voice.muted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
                modifier = Modifier.weight(1f),
                enabled = snapshot.voice.active,
            ) {
                Text(if (snapshot.voice.muted) "Unmute" else "Mute")
            }
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
                enabled = snapshot.connection is ConnectionState.Ready,
            ) {
                Text("Send file")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            snapshot.rooms.forEach { room ->
                val selected = room.id == snapshot.roomId
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable {
                        if (!selected) scope.launch { client.joinRoom(room.id) }
                    },
                )
            }
            Text(
                text = "+",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { showCreateRoom = true }
                    .padding(horizontal = 4.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            snapshot.members.take(6).forEach { member ->
                Text(
                    text = member.name + if (member.selfMute) " 🔇" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (member.sharing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(snapshot.maxChatBytes) },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        scope.launch { client.sendChat(text) }
                    }
                },
                enabled = draft.isNotBlank() && snapshot.connection is ConnectionState.Ready,
            ) { Text("Send") }
        }
    }
}

