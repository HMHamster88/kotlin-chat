package org.kotlinchat.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.client.ServerKeyTrust
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.client.settings.ConnectionMemory
import org.kotlinchat.client.settings.SavedConnection
import org.kotlinchat.client.update.UpdateChecker
import org.kotlinchat.client.update.UpdatePlatform
import javax.swing.JFileChooser

/** jpackage does not expose its version at runtime, so the build passes it as a system property. */
private val desktopVersion: String = System.getProperty("kotlinchat.version") ?: "0.0.0"

@Composable
fun ChatScreen(client: ChatClient, onExit: () -> Unit) {
    val snapshot by client.snapshot.collectAsState()
    val transfers by client.transfers.collectAsState()
    val scope = rememberCoroutineScope()
    val inputDevices = remember(client) { client.inputDevices() }
    val outputDevices = remember(client) { client.outputDevices() }

    val memory = remember { ConnectionMemory() }
    var saved by remember { mutableStateOf(memory.load()) }
    var pendingTrust by remember { mutableStateOf<PendingServerTrust?>(null) }
    var checkingKey by remember { mutableStateOf(false) }

    val updates = remember(scope) {
        // -Dkotlinchat.updateUrl lets a mirror or a local test serve the manifest. Normal builds
        // use the release-owned URL.
        val manifestUrl = System.getProperty("kotlinchat.updateUrl")?.takeIf { it.isNotBlank() }
        DesktopUpdateController(
            desktopVersion,
            scope,
            if (manifestUrl == null) {
                UpdateChecker(desktopVersion, UpdatePlatform.DESKTOP_WINDOWS)
            } else {
                UpdateChecker(desktopVersion, UpdatePlatform.DESKTOP_WINDOWS, manifestUrl)
            },
        )
    }
    val updateState by updates.state.collectAsState()
    DisposableEffect(updates) { onDispose { updates.close() } }

    // One check per launch, only when the user left it enabled. Failures are silent here; the
    // manual check (from the same banner) is the one that reports trouble.
    LaunchedEffect(Unit) {
        updates.startupCleanup()
        if (saved.settings.checkForUpdates) updates.check(saved.settings.skippedVersion)
    }

    fun savePreferences(transform: (ClientSettings) -> ClientSettings) {
        saved = memory.updateSettings(transform)
    }

    val updateBanner: @Composable () -> Unit = {
        UpdateBanner(
            state = updateState,
            onDownload = updates::download,
            onInstall = { updates.installAndRestart(onExit) },
            onLater = updates::dismiss,
            onSkip = {
                val version = (updateState as? UpdateState.Available)?.info?.version
                if (version != null) savePreferences { it.copy(skippedVersion = version) }
                updates.dismiss()
            },
            onOpenPage = {
                openReleasePage((updateState as? UpdateState.Failed)?.info?.notesUrl)
            },
        )
    }

    // Set when Connect is pressed and written once the connection is actually up, so a typo in
    // the address is never remembered. Lives here because the connect screen disappears when
    // the connection succeeds.
    var pendingSave by remember { mutableStateOf<ConnectionRequest?>(null) }

    LaunchedEffect(snapshot.connection) {
        if (snapshot.connection !is ConnectionState.Ready) return@LaunchedEffect
        val request = pendingSave ?: return@LaunchedEffect
        // The connect form does not know about update preferences; keep the stored ones.
        memory.save(
            request.toSettings().copy(
                checkForUpdates = saved.settings.checkForUpdates,
                skippedVersion = saved.settings.skippedVersion,
            ),
            request.password,
        )
        pendingSave = null
        saved = memory.load()
    }

    fun start(request: ConnectionRequest) {
        pendingSave = request
        client.selectAudioDevices(request.devices)
        client.connect(request.endpoint, request.name, request.password)
    }

    // One shot per launch: only when the user asked for it, a password is stored, and the
    // server key is already pinned (an unknown key must be approved in person).
    LaunchedEffect(Unit) {
        val remembered = saved
        val password = remembered.password
        if (!remembered.settings.canAutoConnect || password == null) return@LaunchedEffect
        if (snapshot.room != null || snapshot.connection !is ConnectionState.Idle) return@LaunchedEffect

        val request = ConnectionRequest(
            endpoint = ServerEndpoint(remembered.settings.host, remembered.settings.port, remembered.settings.tls),
            name = remembered.settings.name.ifBlank { "guest" },
            password = password,
            devices = org.kotlinchat.client.rtc.AudioDeviceSelection(
                remembered.settings.inputDeviceId,
                remembered.settings.outputDeviceId,
            ),
            rememberPassword = true,
            autoConnect = true,
        )
        // A failed probe (server down, network) is not a reason to skip: the connect loop
        // retries and reports its own reason. An unknown or changed key still waits for the user.
        val trust = runCatching { client.checkServerKey(request.endpoint) }.getOrNull()
        if (trust == null || trust is ServerKeyTrust.Pinned) start(request)
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

    val failure = snapshot.connection as? ConnectionState.Failed
    if (failure != null || snapshot.room == null) {
        Column(Modifier.fillMaxSize()) {
            updateBanner()
            Box(Modifier.weight(1f)) {
                ConnectScreen(
                    snapshot = snapshot,
                    failure = failure?.failure,
                    inputDevices = inputDevices,
                    outputDevices = outputDevices,
                    checkingKey = checkingKey,
                    initial = saved,
                    canRememberPassword = memory.canRememberPassword,
                    checkForUpdates = saved.settings.checkForUpdates,
                    onCheckForUpdatesChange = { enabled ->
                        savePreferences { it.copy(checkForUpdates = enabled) }
                    },
                    onForgetSaved = {
                        memory.clear()
                        saved = SavedConnection()
                    },
                    onForgetKey = { endpoint -> client.forgetPin(endpoint) },
                    onConnect = { request ->
                        scope.launch {
                            // Ask about an unknown key before anything is pinned; the probe also
                            // turns a changed certificate into a clear question instead of a
                            // late failure.
                            checkingKey = true
                            val trust = runCatching { client.checkServerKey(request.endpoint) }.getOrNull()
                            checkingKey = false
                            if (trust == null || trust is ServerKeyTrust.Pinned) {
                                start(request)
                            } else {
                                pendingTrust = PendingServerTrust(request, trust)
                            }
                        }
                    },
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        updateBanner()
        Box(Modifier.weight(1f)) {
            RoomScreen(
                snapshot = snapshot,
                transfers = transfers,
                onJoinRoom = { roomId -> scope.launch { client.joinRoom(roomId) } },
                onCreateRoom = { name, topic, voice -> scope.launch { client.createRoom(name, topic, voice) } },
                onSend = { text -> scope.launch { client.sendChat(text) } },
                onToggleMute = {
                    scope.launch { client.setMicrophoneMuted(!snapshot.voice.muted) }
                },
                onStartShare = { source -> scope.launch { client.startScreenShare(source) } },
                onStopShare = { scope.launch { client.stopScreenShare() } },
                loadScreenSources = { client.screenSources() },
                onSendFile = {
                    val chooser = JFileChooser().apply { dialogTitle = "Send a file" }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val file = chooser.selectedFile
                        scope.launch { client.offerFile(file.absolutePath) }
                    }
                },
                onAcceptTransfer = { transfer -> scope.launch { client.acceptTransfer(transfer.id, transfer.peerId) } },
                onDeclineTransfer = { transfer -> scope.launch { client.declineTransfer(transfer.id, transfer.peerId) } },
                onCancelTransfer = { transfer -> scope.launch { client.cancelTransfer(transfer.id, transfer.peerId) } },
                onDisconnect = { client.disconnect() },
            )
        }
    }
}
