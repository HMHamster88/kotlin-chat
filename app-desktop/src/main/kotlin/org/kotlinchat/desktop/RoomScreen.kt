package org.kotlinchat.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.file.TransferProgress
import org.kotlinchat.client.rtc.PeerLinkInfo
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.client.rtc.ScreenShareSource
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.RoomSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RoomScreen(
    snapshot: ChatSnapshot,
    transfers: List<TransferProgress>,
    onJoinRoom: (String) -> Unit,
    onSend: (String) -> Unit,
    onToggleMute: () -> Unit,
    onStartShare: (ScreenShareSource) -> Unit,
    onStopShare: () -> Unit,
    loadScreenSources: () -> List<ScreenShareSource>,
    onSendFile: () -> Unit,
    onAcceptTransfer: (TransferProgress) -> Unit,
    onDeclineTransfer: (TransferProgress) -> Unit,
    onCancelTransfer: (TransferProgress) -> Unit,
    onDisconnect: () -> Unit,
) {
    var sharingSources by remember { mutableStateOf<List<ScreenShareSource>?>(null) }

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            snapshot = snapshot,
            onJoinRoom = onJoinRoom,
            modifier = Modifier.width(250.dp).fillMaxHeight(),
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TopBar(
                snapshot = snapshot,
                onToggleMute = onToggleMute,
                onShare = {
                    if (snapshot.screen.sharing) onStopShare() else sharingSources = loadScreenSources()
                },
                onSendFile = onSendFile,
                onDisconnect = onDisconnect,
            )
            if (snapshot.connection !is ConnectionState.Ready) {
                ConnectionBanner(snapshot.connection)
            }
            HorizontalDivider()

            val remote = snapshot.screen.remote
            if (remote != null || snapshot.screen.remoteFrom != null) {
                val sharerName = snapshot.members
                    .firstOrNull { it.sessionId == snapshot.screen.remoteFrom }?.name
                RemoteVideoPane(
                    screen = snapshot.screen,
                    sharerName = sharerName,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                )
                HorizontalDivider()
            }

            MessageList(snapshot, Modifier.weight(1f))
            TransfersPanel(
                transfers = transfers,
                onAccept = onAcceptTransfer,
                onDecline = onDeclineTransfer,
                onCancel = onCancelTransfer,
            )
            HorizontalDivider()
            Composer(snapshot, onSend)
        }
    }

    sharingSources?.let { sources ->
        ShareDialog(
            sources = sources,
            onSelect = { source ->
                sharingSources = null
                onStartShare(source)
            },
            onDismiss = { sharingSources = null },
        )
    }
}

@Composable
private fun TopBar(
    snapshot: ChatSnapshot,
    onToggleMute: () -> Unit,
    onShare: () -> Unit,
    onSendFile: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "# ${snapshot.room?.room?.name ?: "…"}",
                style = MaterialTheme.typography.titleMedium,
            )
            val topic = snapshot.room?.room?.topic.orEmpty()
            if (topic.isNotBlank()) {
                Text(
                    text = topic,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        snapshot.latencyMs?.let { latency ->
            Text(
                text = "$latency ms",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (snapshot.voice.active) {
            OutlinedButton(onClick = onShare) {
                Text(if (snapshot.screen.sharing) "Stop sharing" else "Share screen")
            }
            OutlinedButton(onClick = onSendFile) { Text("Send file") }

            val muted = snapshot.voice.muted
            Button(
                onClick = onToggleMute,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (muted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
            ) {
                Text(if (muted) "Unmute" else "Mute")
            }
        }

        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
    }
}

@Composable
private fun ConnectionBanner(state: ConnectionState) {
    val background = when (state) {
        is ConnectionState.Failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Sidebar(
    snapshot: ChatSnapshot,
    onJoinRoom: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = snapshot.serverName.ifBlank { "server" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))

        Text("ROOMS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        snapshot.rooms.forEach { room ->
            RoomRow(
                room = room,
                selected = room.id == snapshot.roomId,
                onClick = { onJoinRoom(room.id) },
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Text(
            "MEMBERS — ${snapshot.members.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        snapshot.members.forEach { member ->
            MemberRow(
                member = member,
                isSelf = member.sessionId == snapshot.self?.sessionId,
                link = snapshot.voice.links[member.sessionId],
            )
        }

        Spacer(Modifier.weight(1f))
        VoiceStatus(snapshot)
        val fingerprint = snapshot.serverFingerprint
        if (fingerprint != null) {
            Text(
                text = "key ${fingerprint.take(17)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceStatus(snapshot: ChatSnapshot) {
    val voice = snapshot.voice
    when {
        voice.error != null -> Text(
            text = "voice: ${voice.error}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        voice.active -> Text(
            text = "voice ${voice.connectedPeers}/${voice.links.size} · " +
                if (voice.muted) "mic off" else "live",
            style = MaterialTheme.typography.labelSmall,
            color = if (voice.muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun RoomRow(room: RoomSummary, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(room.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (room.cap > 0) "${room.memberCount}/${room.cap}" else "${room.memberCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemberRow(member: Member, isSelf: Boolean, link: PeerLinkInfo?) {
    val level = link?.level ?: 0f
    val dotColor = when {
        member.selfMute -> MaterialTheme.colorScheme.outline
        link?.state == PeerLinkState.FAILED -> MaterialTheme.colorScheme.error
        link?.state == PeerLinkState.CONNECTED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val dotSize = (6f + 6f * level.coerceIn(0f, 1f)).dp

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(dotSize).background(color = dotColor, shape = RoundedCornerShape(50)))
        }
        Text(
            text = member.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (member.selfMute) {
            Text(
                "(muted)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (member.sharing) {
            Text(
                "(presenting)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (link?.relayed == true) {
            Text(
                "(relay)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageList(snapshot: ChatSnapshot, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val messages = snapshot.messages

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (messages.isEmpty()) {
            item {
                Text(
                    text = "No messages yet. Say something.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(messages, key = { it.messageId }) { message ->
            MessageRow(message = message, isSelf = message.from == snapshot.self?.sessionId)
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage, isSelf: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = clock(message.ts),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp),
        )
        Text(
            text = message.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Composer(snapshot: ChatSnapshot, onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val ready = snapshot.connection is ConnectionState.Ready

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        onSend(text)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(snapshot.maxChatBytes) },
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        submit()
                        true
                    } else {
                        false
                    }
                },
            placeholder = { Text("Message #${snapshot.room?.room?.name ?: ""}") },
            maxLines = 4,
        )
        Button(onClick = { submit() }, enabled = ready && draft.isNotBlank()) { Text("Send") }
    }
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormat)

@Composable
private fun ShareDialog(
    sources: List<ScreenShareSource>,
    onSelect: (ScreenShareSource) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Share a screen") },
        text = {
            if (sources.isEmpty()) {
                Text("Screen capture is not available on this system.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(sources, key = { "${it.isWindow}:${it.id}" }) { source ->
                        TextButton(
                            onClick = { onSelect(source) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = (if (source.isWindow) "Window · " else "Screen · ") + source.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}
