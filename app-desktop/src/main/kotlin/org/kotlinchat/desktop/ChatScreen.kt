package org.kotlinchat.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.ServerKeyTrust
import javax.swing.JFileChooser

@Composable
fun ChatScreen(client: ChatClient) {
    val snapshot by client.snapshot.collectAsState()
    val transfers by client.transfers.collectAsState()
    val scope = rememberCoroutineScope()
    val inputDevices = remember(client) { client.inputDevices() }
    val outputDevices = remember(client) { client.outputDevices() }
    var pendingTrust by remember { mutableStateOf<PendingServerTrust?>(null) }
    var checkingKey by remember { mutableStateOf(false) }

    val pending = pendingTrust
    if (pending != null) {
        TrustDialog(
            pending = pending,
            onConfirm = {
                if (pending.trust is ServerKeyTrust.Changed) client.forgetPin(pending.endpoint)
                client.trustServerKey(pending.endpoint, pending.trust.approvedFingerprint)
                pendingTrust = null
                client.selectAudioDevices(pending.devices)
                client.connect(pending.endpoint, pending.name, pending.password)
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
            onForgetKey = { endpoint -> client.forgetPin(endpoint) },
            onConnect = { endpoint, name, password, devices ->
                scope.launch {
                    // Ask about an unknown key before anything is pinned; the probe also turns a
                    // changed certificate into a clear question instead of a late failure.
                    checkingKey = true
                    val trust = runCatching { client.checkServerKey(endpoint) }.getOrNull()
                    checkingKey = false
                    if (trust == null || trust is ServerKeyTrust.Pinned) {
                        client.selectAudioDevices(devices)
                        client.connect(endpoint, name, password)
                    } else {
                        pendingTrust = PendingServerTrust(endpoint, name, password, devices, trust)
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
