package org.kotlinchat.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.file.TransferDirection
import org.kotlinchat.client.file.TransferGroup
import org.kotlinchat.client.file.TransferProgress
import org.kotlinchat.client.file.TransferState
import org.kotlinchat.client.file.groupedForChat
import org.kotlinchat.protocol.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Messages and files share one list, in the order they happened. */
private sealed interface FeedItem {
    val ts: Long

    data class Text(val message: ChatMessage) : FeedItem {
        override val ts: Long get() = message.ts
    }

    data class File(val group: TransferGroup) : FeedItem {
        override val ts: Long get() = group.ts
    }
}

@Composable
fun ChatFeed(
    snapshot: ChatSnapshot,
    transfers: List<TransferProgress>,
    onAccept: (TransferProgress) -> Unit,
    onDecline: (TransferProgress) -> Unit,
    onCancel: (TransferProgress) -> Unit,
    clock24h: Boolean = true,
    showTimestamps: Boolean = true,
    groupMessages: Boolean = true,
    textScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val feed = remember(snapshot.messages, transfers) {
        val texts = snapshot.messages.map { FeedItem.Text(it) }
        val files = transfers.groupedForChat().map { FeedItem.File(it) }
        (texts + files).sortedBy { it.ts }
    }

    LaunchedEffect(feed.size) {
        if (feed.isNotEmpty()) listState.animateScrollToItem(feed.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (feed.isEmpty()) {
            item {
                Text(
                    text = "No messages yet. Say something.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(feed, key = { _, item -> keyOf(item) }) { index, item ->
            val previous = feed.getOrNull(index - 1)
            val grouped = groupMessages &&
                item is FeedItem.Text &&
                previous is FeedItem.Text &&
                previous.message.from == item.message.from &&
                item.ts - previous.ts in 0L..GROUP_WINDOW_MS
            when (item) {
                is FeedItem.Text -> MessageRow(
                    message = item.message,
                    isSelf = item.message.from == snapshot.self?.sessionId,
                    clock24h = clock24h,
                    showTimestamps = showTimestamps,
                    grouped = grouped,
                    textScale = textScale,
                )

                is FeedItem.File -> FileRow(
                    group = item.group,
                    selfName = snapshot.self?.name,
                    clock24h = clock24h,
                    showTimestamps = showTimestamps,
                    onAccept = onAccept,
                    onDecline = onDecline,
                    onCancel = onCancel,
                )
            }
        }
    }
}

private fun keyOf(item: FeedItem): String = when (item) {
    is FeedItem.Text -> item.message.messageId
    is FeedItem.File -> "file:${item.group.direction}:${item.group.id}"
}

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

private fun clock(epochMillis: Long, clock24h: Boolean): String =
    SimpleDateFormat(if (clock24h) "HH:mm" else "hh:mm a", Locale.getDefault())
        .format(Date(epochMillis))

@Composable
private fun MessageRow(
    message: ChatMessage,
    isSelf: Boolean,
    clock24h: Boolean,
    showTimestamps: Boolean,
    grouped: Boolean,
    textScale: Float,
) {
    val body = MaterialTheme.typography.bodySmall.let {
        it.copy(fontSize = it.fontSize * textScale.coerceIn(0.7f, 1.6f))
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (grouped) Modifier.padding(start = 42.dp) else Modifier,
    ) {
        if (showTimestamps && !grouped) {
            Text(clock(message.ts, clock24h), style = MaterialTheme.typography.labelSmall)
        }
        if (!grouped) {
            Text(
                text = message.name,
                style = body,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(message.text, style = body, modifier = Modifier.weight(1f))
    }
}

/** A file in the chat stream: one bubble per offer, with a line per receiver. */
@Composable
private fun FileRow(
    group: TransferGroup,
    selfName: String?,
    clock24h: Boolean,
    showTimestamps: Boolean,
    onAccept: (TransferProgress) -> Unit,
    onDecline: (TransferProgress) -> Unit,
    onCancel: (TransferProgress) -> Unit,
) {
    val isSelf = group.direction == TransferDirection.OUTGOING
    val sender = if (isSelf) selfName ?: "you" else group.rows.first().peerName
    val time = clock(group.ts, clock24h)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sender,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (showTimestamps) {
                Text(time, style = MaterialTheme.typography.labelSmall)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📄", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            text = group.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = size(group.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (group.caption.isNotBlank()) {
                    Text(
                        text = group.caption,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                group.rows.forEach { row ->
                    FileReceiverLine(row, group.direction, onAccept, onDecline, onCancel)
                }
            }
        }
    }
}

@Composable
private fun FileReceiverLine(
    row: TransferProgress,
    direction: TransferDirection,
    onAccept: (TransferProgress) -> Unit,
    onDecline: (TransferProgress) -> Unit,
    onCancel: (TransferProgress) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status(row, direction),
                style = MaterialTheme.typography.labelSmall,
                color = if (row.state == TransferState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            when {
                direction == TransferDirection.INCOMING && row.state == TransferState.OFFERED -> {
                    TextButton(onClick = { onAccept(row) }) { Text("Accept") }
                    TextButton(onClick = { onDecline(row) }) { Text("Decline") }
                }

                row.state == TransferState.OFFERED || row.isActive ->
                    TextButton(onClick = { onCancel(row) }) { Text("Cancel") }

                // The file is in Downloads already; one tap opens it.
                row.state == TransferState.COMPLETED && direction == TransferDirection.INCOMING ->
                    if (row.savedUri?.startsWith("content://") == true) {
                        val context = LocalContext.current
                        TextButton(onClick = { openDownloadedFile(context, row) }) { Text("Open") }
                    }

                else -> Unit
            }
        }
        if (row.isActive && row.size > 0) {
            LinearProgressIndicator(
                progress = { row.fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

private fun status(row: TransferProgress, direction: TransferDirection): String {
    val to = if (direction == TransferDirection.OUTGOING) "to ${row.peerName}" else "from ${row.peerName}"
    return when (row.state) {
        TransferState.OFFERED ->
            if (direction == TransferDirection.INCOMING) "wants to send you ${size(row.size)}"
            else "waiting for someone to accept"

        TransferState.ACCEPTED, TransferState.ACTIVE ->
            "${size(row.bytes)} / ${size(row.size)} $to"

        TransferState.COMPLETED ->
            row.savedPath?.let { "saved to $it" } ?: "sent $to"

        TransferState.DECLINED -> "declined${row.error?.let { " · $it" } ?: ""}"
        TransferState.CANCELLED -> "cancelled"
        TransferState.FAILED -> "failed${row.error?.let { " · $it" } ?: ""}"
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GiB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MiB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.1f KiB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
