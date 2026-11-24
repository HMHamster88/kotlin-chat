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
fun ChatScreen(
    client: ChatClient,
    memory: ConnectionMemory,
    saved: SavedConnection,
    onSavedChange: (SavedConnection) -> Unit,
    settingsOpen: Boolean,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val snapshot by client.snapshot.collectAsState()
    val transfers by client.transfers.collectAsState()
    val scope = rememberCoroutineScope()
    var deviceRefresh by remember { mutableStateOf(0) }
    val inputDevices = remember(client, deviceRefresh) { client.inputDevices() }
    val outputDevices = remember(client, deviceRefresh) { client.outputDevices() }

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
        onSavedChange(memory.updateSettings(transform))
    }

    // Keep the client's live copies in step with the stored preferences.
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
    val seenTransfers = remember { mutableMapOf<String, org.kotlinchat.client.file.TransferState>() }
    LaunchedEffect(transfers, saved.settings.openDownloadedFiles) {
        val open = saved.settings.openDownloadedFiles
        transfers.forEach { transfer ->
            val before = seenTransfers[transfer.key]
            if (open &&
                transfer.direction == org.kotlinchat.client.file.TransferDirection.INCOMING &&
                transfer.state == org.kotlinchat.client.file.TransferState.COMPLETED &&
                before != org.kotlinchat.client.file.TransferState.COMPLETED
            ) {
                transfer.savedPath?.let(::openDownloadedFile)
            }
            seenTransfers[transfer.key] = transfer.state
        }
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
            request.toSettings(saved.settings),
            request.password,
        )
        pendingSave = null
        onSavedChange(memory.load())
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

    if (settingsOpen) {
        SettingsDialog(
            settings = saved.settings,
            snapshot = snapshot,
            hasSavedPassword = saved.password != null,
            canRememberPassword = memory.canRememberPassword,
            version = desktopVersion,
            inputDevices = inputDevices,
            outputDevices = outputDevices,
            currentRoomId = snapshot.roomId,
            onRefreshDevices = { deviceRefresh++ },
            onSettingsChange = { updated -> onSavedChange(memory.updateSettings { updated }) },
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
                onSavedChange(SavedConnection())
                client.disconnect()
                onCloseSettings()
            },
            onForgetPin = {
                client.forgetPin(
                    ServerEndpoint(saved.settings.host, saved.settings.port, saved.settings.tls),
                )
            },
            onChangeServer = {
                client.disconnect()
                onCloseSettings()
            },
            onReconnect = {
                val password = saved.password
                if (password == null) {
                    client.disconnect()
                } else {
                    start(
                        ConnectionRequest(
                            endpoint = ServerEndpoint(
                                saved.settings.host,
                                saved.settings.port,
                                saved.settings.tls,
                            ),
                            name = saved.settings.name.ifBlank { "guest" },
                            password = password,
                            devices = org.kotlinchat.client.rtc.AudioDeviceSelection(
                                saved.settings.inputDeviceId,
                                saved.settings.outputDeviceId,
                            ),
                            rememberPassword = saved.settings.rememberPassword,
                            autoConnect = saved.settings.autoConnect,
                        ),
                    )
                }
                onCloseSettings()
            },
            onCheckUpdates = updates::checkNow,
            onOpenReleasePage = {
                openReleasePage((updateState as? UpdateState.Failed)?.info?.notesUrl)
            },
            onChooseDownloadFolder = {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Download folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    savePreferences { it.copy(downloadDirectory = chooser.selectedFile.absolutePath) }
                }
            },
            onOpenDownloads = { openDownloadsFolder(saved.settings.downloadDirectory) },
            onClearHistory = { roomId ->
                client.clearHistory(
                    ServerEndpoint(saved.settings.host, saved.settings.port, saved.settings.tls),
                    roomId,
                )
            },
            onClose = onCloseSettings,
        )
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
    val ready = snapshot.connection is ConnectionState.Ready
    if (failure != null || (snapshot.room == null && !(ready && snapshot.roomless))) {
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
                        onSavedChange(SavedConnection())
                    },
                    onForgetKey = { endpoint -> client.forgetPin(endpoint) },
                    onOpenSettings = onOpenSettings,
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

    if (snapshot.room == null) {
        Column(Modifier.fillMaxSize()) {
            updateBanner()
            Box(Modifier.weight(1f)) {
                ServerScreen(
                    snapshot = snapshot,
                    onJoinRoom = { roomId -> scope.launch { client.joinRoom(roomId) } },
                    onCreateRoom = { name, topic, voice -> scope.launch { client.createRoom(name, topic, voice) } },
                    onOpenSettings = onOpenSettings,
                    onDisconnect = { client.disconnect() },
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
                settings = saved.settings,
                onJoinRoom = { roomId -> scope.launch { client.joinRoom(roomId) } },
                onCreateRoom = { name, topic, voice -> scope.launch { client.createRoom(name, topic, voice) } },
                onSend = { text -> scope.launch { client.sendChat(text) } },
                onToggleMute = {
                    scope.launch { client.setMicrophoneMuted(!snapshot.voice.muted) }
                },
                onToggleSpeakerMute = {
                    scope.launch { client.setSpeakerMuted(!snapshot.voice.speakerMuted) }
                },
                onStartShare = { source ->
                    scope.launch {
                        client.startScreenShare(
                            source,
                            saved.settings.shareMaxWidth,
                            saved.settings.shareMaxHeight,
                            saved.settings.shareFps,
                        )
                    }
                },
                onStopShare = { scope.launch { client.stopScreenShare() } },
                loadScreenSources = { client.screenSources() },
                onSendFile = { path, caption -> scope.launch { client.offerFile(path, caption) } },
                onAcceptTransfer = { transfer -> scope.launch { client.acceptTransfer(transfer.id, transfer.peerId) } },
                onDeclineTransfer = { transfer -> scope.launch { client.declineTransfer(transfer.id, transfer.peerId) } },
                onCancelTransfer = { transfer -> scope.launch { client.cancelTransfer(transfer.id, transfer.peerId) } },
                onLeaveRoom = { scope.launch { client.leaveRoom() } },
                onOpenSettings = onOpenSettings,
            )
        }
    }
}
