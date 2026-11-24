package org.kotlinchat.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.protocol.RoomSummary

/**
 * Shown while the client is authenticated to a server but deliberately outside every room.
 * The call is stopped in this state, so this screen is the only place to pick the next room.
 */
@Composable
fun ServerScreen(
    snapshot: ChatSnapshot,
    onJoinRoom: (String) -> Unit,
    onCreateRoom: (name: String, topic: String, voice: Boolean) -> Unit,
    onDeleteRoom: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var showCreateRoom by remember { mutableStateOf(false) }
    var roomToDelete by remember { mutableStateOf<RoomSummary?>(null) }

    if (showCreateRoom) {
        CreateRoomDialog(
            onCreate = { name, topic, voice ->
                showCreateRoom = false
                onCreateRoom(name, topic, voice)
            },
            onDismiss = { showCreateRoom = false },
        )
    }

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

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = snapshot.serverName.ifBlank { "server" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Connected · not in a room",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(MdiIcons.cog, contentDescription = "Settings")
            }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (snapshot.motd.isNotBlank()) {
                Text(
                    text = snapshot.motd,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snapshot.lastError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rooms", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose a room to join the conversation and start voice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(snapshot.rooms, key = { it.id }) { room ->
                    RoomChoice(
                        room = room,
                        canDelete = room.id in snapshot.deletableRoomIds,
                        onClick = { onJoinRoom(room.id) },
                        onDelete = { roomToDelete = room },
                    )
                }
            }

            OutlinedButton(
                onClick = { showCreateRoom = true },
                modifier = Modifier.widthIn(max = 720.dp),
            ) {
                Icon(MdiIcons.plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("New room")
            }
        }
    }
}

@Composable
private fun RoomChoice(room: RoomSummary, canDelete: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (room.topic.isNotBlank()) {
                    Text(
                        text = room.topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = if (room.cap > 0) "${room.memberCount}/${room.cap}" else "${room.memberCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = MdiIcons.deleteOutline,
                        contentDescription = "Remove ${room.name}",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = "Join",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
