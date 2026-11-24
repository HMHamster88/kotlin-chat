package org.kotlinchat.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.client.file.TransferProgress
import org.kotlinchat.client.rtc.PeerLinkInfo
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.client.rtc.ScreenShareSource
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.RoomSummary
import javax.swing.JFileChooser

@Composable
fun RoomScreen(
    snapshot: ChatSnapshot,
    transfers: List<TransferProgress>,
    settings: ClientSettings,
    onJoinRoom: (String) -> Unit,
    onCreateRoom: (name: String, topic: String, voice: Boolean) -> Unit,
    onDeleteRoom: (String) -> Unit,
    onSend: (String) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onStartShare: (ScreenShareSource) -> Unit,
    onStopShare: () -> Unit,
    loadScreenSources: () -> List<ScreenShareSource>,
    onSendFile: (path: String, caption: String) -> Unit,
    onAcceptTransfer: (TransferProgress) -> Unit,
    onDeclineTransfer: (TransferProgress) -> Unit,
    onCancelTransfer: (TransferProgress) -> Unit,
    onLeaveRoom: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var sharingSources by remember { mutableStateOf<List<ScreenShareSource>?>(null) }

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            snapshot = snapshot,
            onJoinRoom = onJoinRoom,
            onCreateRoom = onCreateRoom,
            onDeleteRoom = onDeleteRoom,
            onOpenSettings = onOpenSettings,
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
                onToggleSpeakerMute = onToggleSpeakerMute,
                onShare = {
                    if (snapshot.screen.sharing) onStopShare() else sharingSources = loadScreenSources()
                },
                onLeaveRoom = onLeaveRoom,
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

            ChatFeed(
                snapshot = snapshot,
                transfers = transfers,
                onAccept = onAcceptTransfer,
                onDecline = onDeclineTransfer,
                onCancel = onCancelTransfer,
                clock24h = settings.clock24h,
                showTimestamps = settings.showTimestamps,
                groupMessages = settings.groupMessages,
                textScale = settings.textScale,
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider()
            Composer(snapshot, settings.enterSends, onSend, onSendFile)
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
    onToggleSpeakerMute: () -> Unit,
    onShare: () -> Unit,
    onLeaveRoom: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "# ${snapshot.room?.room?.name ?: "…"}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val topic = snapshot.room?.room?.topic.orEmpty()
            if (topic.isNotBlank()) {
                Text(
                    text = topic,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            VoiceToggleButton(
                muted = snapshot.voice.muted,
                icon = if (snapshot.voice.muted) MdiIcons.microphoneOff else MdiIcons.microphone,
                contentDescription = if (snapshot.voice.muted) "Unmute microphone" else "Mute microphone",
                onClick = onToggleMute,
            )
            VoiceToggleButton(
                muted = snapshot.voice.speakerMuted,
                icon = if (snapshot.voice.speakerMuted) MdiIcons.volumeOff else MdiIcons.volumeHigh,
                contentDescription = if (snapshot.voice.speakerMuted) "Unmute speaker" else "Mute speaker",
                onClick = onToggleSpeakerMute,
            )
        }

        OutlinedIconButton(onClick = onLeaveRoom, modifier = Modifier.size(48.dp)) {
            Icon(MdiIcons.exitToApp, contentDescription = "Leave room")
        }
    }
}

@Composable
private fun VoiceToggleButton(
    muted: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (muted) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = if (muted) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
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
    onCreateRoom: (String, String, Boolean) -> Unit,
    onDeleteRoom: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateRoom by remember { mutableStateOf(false) }
    var roomToDelete by remember { mutableStateOf<RoomSummary?>(null) }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = snapshot.serverName.ifBlank { "server" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onOpenSettings) {
                Icon(MdiIcons.cog, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Settings")
            }
        }
        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ROOMS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showCreateRoom = true }) {
                Icon(MdiIcons.plus, contentDescription = "New room")
            }
        }
        snapshot.rooms.forEach { room ->
            RoomRow(
                room = room,
                selected = room.id == snapshot.roomId,
                canDelete = room.id in snapshot.deletableRoomIds,
                onClick = { onJoinRoom(room.id) },
                onDelete = { roomToDelete = room },
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

        if (showCreateRoom) {
            CreateRoomDialog(
                onCreate = { name, topic, voice ->
                    showCreateRoom = false
                    onCreateRoom(name, topic, voice)
                },
                onDismiss = { showCreateRoom = false },
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

        voice.active -> Column {
            Text(
                text = "voice ${voice.connectedPeers}/${voice.links.size} · " +
                    if (voice.muted) "mic off" else "live",
                style = MaterialTheme.typography.labelSmall,
                color = if (voice.muted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            // Without STUN/TURN the peers only have host candidates, which never work outside a
            // LAN. Say so instead of leaving two people staring at "connecting".
            if (!voice.hasRelay) {
                Text(
                    text = "no STUN/TURN: peers behind NAT cannot connect",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RoomRow(
    room: RoomSummary,
    selected: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = room.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (room.cap > 0) "${room.memberCount}/${room.cap}" else "${room.memberCount}",
            style = MaterialTheme.typography.labelSmall,
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
            Icon(
                imageVector = MdiIcons.microphoneOff,
                contentDescription = "Muted",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun Composer(
    snapshot: ChatSnapshot,
    enterSends: Boolean,
    onSend: (String) -> Unit,
    onSendFile: (path: String, caption: String) -> Unit,
) {
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var pending by remember { mutableStateOf<PendingFile?>(null) }
    val ready = snapshot.connection is ConnectionState.Ready
    val limit = snapshot.maxChatBytes

    fun pickFile() {
        val chooser = JFileChooser().apply { dialogTitle = "Send a file" }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            pending = PendingFile(file.absolutePath, file.name)
        }
    }

    fun submit() {
        val text = draft.text.trim()
        val file = pending
        if (file != null) {
            pending = null
            draft = TextFieldValue("")
            onSendFile(file.path, text)
            return
        }
        if (text.isEmpty()) return
        draft = TextFieldValue("")
        onSend(text)
    }

    Column(Modifier.fillMaxWidth()) {
        pending?.let { file ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { pending = null }) { Text("Remove") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { pickFile() }) {
                AttachFileIcon(contentDescription = "Attach file")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { value -> draft = value.limitedTo(limit) },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // Enter sends; Shift+Enter is inserted here rather than left to the
                            // text field, so a line break never depends on platform key handling.
                            Key.Enter, Key.NumPadEnter -> {
                                when {
                                    event.isShiftPressed -> draft = draft.withLineBreak(limit)
                                    enterSends || event.isCtrlPressed -> submit()
                                    // Enter inserts a newline when "Enter sends" is off.
                                    else -> draft = draft.withLineBreak(limit)
                                }
                                true
                            }

                            else -> false
                        }
                    },
                placeholder = {
                    Text(
                        if (pending != null) {
                            "Add a description (optional)"
                        } else {
                            "Message #${snapshot.room?.room?.name ?: ""}"
                        },
                    )
                },
                maxLines = 4,
            )
            Button(
                onClick = { submit() },
                enabled = ready && (draft.text.isNotBlank() || pending != null),
            ) { Text("Send") }
        }
    }
}

private data class PendingFile(val path: String, val name: String)
private fun TextFieldValue.limitedTo(limit: Int): TextFieldValue {
    if (text.length <= limit) return this
    val capped = text.take(limit)
    return TextFieldValue(capped, selection = TextRange(minOf(selection.start, capped.length)))
}

private fun TextFieldValue.withLineBreak(limit: Int): TextFieldValue {
    if (text.length >= limit) return this
    val start = selection.min
    val end = selection.max
    val updated = text.substring(0, start) + "\n" + text.substring(end)
    return TextFieldValue(updated, selection = TextRange(start + 1))
}

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
