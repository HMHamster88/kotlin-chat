package org.kotlinchat.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ConnectionState

@Composable
fun ChatScreen(client: ChatClient) {
    val snapshot by client.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val inputDevices = remember(client) { client.inputDevices() }
    val outputDevices = remember(client) { client.outputDevices() }

    val failure = snapshot.connection as? ConnectionState.Failed
    if (failure != null || snapshot.room == null) {
        ConnectScreen(
            snapshot = snapshot,
            failure = failure?.reason,
            inputDevices = inputDevices,
            outputDevices = outputDevices,
            onConnect = { endpoint, name, password, devices ->
                client.selectAudioDevices(devices)
                client.connect(endpoint, name, password)
            },
        )
        return
    }

    RoomScreen(
        snapshot = snapshot,
        onJoinRoom = { roomId -> scope.launch { client.joinRoom(roomId) } },
        onSend = { text -> scope.launch { client.sendChat(text) } },
        onToggleMute = {
            scope.launch { client.setMicrophoneMuted(!snapshot.voice.muted) }
        },
        onDisconnect = { client.disconnect() },
    )
}
