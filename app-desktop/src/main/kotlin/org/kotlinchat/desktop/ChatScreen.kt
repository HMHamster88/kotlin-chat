package org.kotlinchat.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.client.ServerKeyTrust
import org.kotlinchat.client.settings.ConnectionMemory
import org.kotlinchat.client.settings.SavedConnection
import javax.swing.JFileChooser

@Composable
fun ChatScreen(client: ChatClient) {
    val snapshot by client.snapshot.collectAsState()
    val transfers by client.transfers.collectAsState()
    val scope = rememberCoroutineScope()
    val inputDevices = remember(client) { client.inputDevices() }
    val outputDevices = remember(client) { client.outputDevices() }

    val memory = remember { ConnectionMemory() }
    var saved by remember { mutableStateOf(memory.load()) }
    var pendingTrust by remember { mutableStateOf<PendingServerTrust?>(null) }
    var checkingKey by remember { mutableStateOf(false) }

    // Set when Connect is pressed and written once the connection is actually up, so a typo in
    // the address is never remembered. Lives here because the connect screen disappears when
    // the connection succeeds.
    var pendingSave by remember { mutableStateOf<ConnectionRequest?>(null) }

    LaunchedEffect(snapshot.connection) {
        if (snapshot.connection !is ConnectionState.Ready) return@LaunchedEffect
        val request = pendingSave ?: return@LaunchedEffect
        memory.save(request.toSettings(), request.password)
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
        val pinned = runCatching { client.checkServerKey(request.endpoint) }.getOrNull()
        if (pinned is ServerKeyTrust.Pinned) start(request)
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
        ConnectScreen(
            snapshot = snapshot,
            failure = failure?.failure,
            inputDevices = inputDevices,
            outputDevices = outputDevices,
            checkingKey = checkingKey,
            initial = saved,
            canRememberPassword = memory.canRememberPassword,
            onForgetSaved = {
                memory.clear()
                saved = SavedConnection()
            },
            onForgetKey = { endpoint -> client.forgetPin(endpoint) },
            onConnect = { request ->
                scope.launch {
                    // Ask about an unknown key before anything is pinned; the probe also turns a
                    // changed certificate into a clear question instead of a late failure.
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
        return
    }

    RoomScreen(
        snapshot = snapshot,
        transfers = transfers,
        onJoinRoom = { roomId -> scope.launch { client.joinRoom(roomId) } },
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
