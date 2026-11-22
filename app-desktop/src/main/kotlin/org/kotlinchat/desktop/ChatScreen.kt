package org.kotlinchat.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ConnectionState

@Composable
fun ChatScreen(client: ChatClient) {
    val snapshot by client.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    val failure = snapshot.connection as? ConnectionState.Failed
    if (failure != null || snapshot.room == null) {
        ConnectScreen(
            snapshot = snapshot,
            failure = failure?.reason,
            onConnect = { endpoint, name, password -> client.connect(endpoint, name, password) },
        )
        return
    }

    RoomScreen(
        snapshot = snapshot,
        onJoinRoom = { roomId -> scope.launch { client.joinRoom(roomId) } },
        onSend = { text -> scope.launch { client.sendChat(text) } },
        onDisconnect = { client.disconnect() },
    )
}
