package org.kotlinchat.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.file.TransferDirection
import org.kotlinchat.client.file.TransferGroup
import org.kotlinchat.client.file.TransferProgress
import org.kotlinchat.client.file.TransferState
import org.kotlinchat.client.file.groupedForChat
import org.kotlinchat.client.text.MessageLinks
import org.kotlinchat.protocol.ChatMessage

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
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (feed.isEmpty()) {
            item {
                Text(
                    text = "No messages yet. Say something.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }
        itemsIndexed(feed, key = { _, item -> keyOf(item) }) { index, item ->
            val previous = feed.getOrNull(index - 1)
            val dayChanged = previous == null || !sameDay(previous.ts, item.ts)
            val grouped = !dayChanged &&
                groupMessages &&
                previous != null &&
                senderKey(previous, snapshot) == senderKey(item, snapshot) &&
                item.ts - previous.ts in 0L..GROUP_WINDOW_MS
            Column {
                if (dayChanged) DayDivider(dayLabel(item.ts))
                when (item) {
                    is FeedItem.Text -> {
                        val isSelf = item.message.from == snapshot.self?.sessionId ||
                            item.message.name == snapshot.self?.name
                        MessageBubble(
                            message = item.message,
                            isSelf = isSelf,
                            showName = !isSelf && !grouped,
                            clock24h = clock24h,
                            showTimestamps = showTimestamps,
                            newGroup = !grouped,
                            textScale = textScale,
                        )
                    }

                    is FeedItem.File -> FileBubble(
                        group = item.group,
                        selfName = snapshot.self?.name,
                        showName = !grouped,
                        clock24h = clock24h,
                        showTimestamps = showTimestamps,
                        newGroup = !grouped,
                        onAccept = onAccept,
                        onDecline = onDecline,
                        onCancel = onCancel,
                    )
                }
            }
        }
    }
}

private fun keyOf(item: FeedItem): String = when (item) {
    is FeedItem.Text -> item.message.messageId
    is FeedItem.File -> "file:${item.group.direction}:${item.group.id}"
}

private fun senderKey(item: FeedItem, snapshot: ChatSnapshot): String = when (item) {
    is FeedItem.Text -> item.message.name
    is FeedItem.File -> if (item.group.direction == TransferDirection.OUTGOING) {
        snapshot.self?.name ?: "self"
    } else {
        item.group.rows.firstOrNull()?.peerName ?: "peer"
    }
}

@Composable
private fun DayDivider(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isSelf: Boolean,
    showName: Boolean,
    clock24h: Boolean,
    showTimestamps: Boolean,
    newGroup: Boolean,
    textScale: Float,
) {
    val context = LocalContext.current
    val viewConfiguration = LocalViewConfiguration.current
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    var pressStartedAt by remember(message.messageId) { mutableStateOf(0L) }
    var longPressAt by remember(message.messageId) { mutableStateOf(0L) }
    val body = MaterialTheme.typography.bodyMedium.let {
        it.copy(fontSize = it.fontSize * textScale.coerceIn(0.7f, 1.6f))
    }
    val bubbleColor = if (isSelf) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isSelf) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val metaColor = if (isSelf) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .padding(top = if (newGroup) 7.dp else 0.dp),
            horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
        ) {
            if (showName) {
                Text(
                    text = message.name,
                    modifier = Modifier.padding(start = 12.dp, bottom = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = memberColor(message.name),
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = bubbleColor,
                modifier = Modifier.pointerInput(message.text) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        pressStartedAt = SystemClock.uptimeMillis()
                        longPressAt = 0L
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val elapsed = SystemClock.uptimeMillis() - pressStartedAt
                            if (elapsed >= longPressTimeout) {
                                longPressAt = SystemClock.uptimeMillis()
                                copyMessage(context, message.text)
                                break
                            }
                            val moved = (change.position - down.position).getDistance() >
                                viewConfiguration.touchSlop
                            if (moved || !change.pressed) break
                        }
                    }
                },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    SelectionContainer {
                        LinkifiedText(
                            text = message.text,
                            style = body,
                            color = contentColor,
                            linkColor = if (isSelf) contentColor else MaterialTheme.colorScheme.primary,
                            suppressOpen = {
                                SystemClock.uptimeMillis() - longPressAt < longPressTimeout
                            },
                        )
                    }
                    if (showTimestamps) {
                        Text(
                            text = clock(message.ts, clock24h),
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                        )
                    }
                }
            }
        }
    }
}

private fun copyMessage(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Kotlin Chat", text))
        Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LinkifiedText(
    text: String,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    suppressOpen: () -> Boolean = { false },
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val context = LocalContext.current
    val annotated = remember(text, linkColor, context) {
        val links = MessageLinks.find(text)
        if (links.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                for (link in links) {
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = link.url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline,
                                ),
                                pressedStyle = SpanStyle(
                                    color = linkColor.copy(alpha = 0.68f),
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                            linkInteractionListener = object : LinkInteractionListener {
                                override fun onClick(annotation: LinkAnnotation) {
                                    if (suppressOpen()) return
                                    val target = (annotation as? LinkAnnotation.Clickable)?.tag
                                        ?: return
                                    openExternalLink(context, target)
                                }
                            },
                        ),
                        link.start,
                        link.end,
                    )
                }
            }
        }
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** A file in the chat stream: one bubble per offer, with a line per receiver. */
@Composable
private fun FileBubble(
    group: TransferGroup,
    selfName: String?,
    showName: Boolean,
    clock24h: Boolean,
    showTimestamps: Boolean,
    newGroup: Boolean,
    onAccept: (TransferProgress) -> Unit,
    onDecline: (TransferProgress) -> Unit,
    onCancel: (TransferProgress) -> Unit,
) {
    val isSelf = group.direction == TransferDirection.OUTGOING
    val sender = if (isSelf) selfName ?: "you" else group.rows.first().peerName
    val preview = group.preview
    var showPreview by remember(group.id, preview) { mutableStateOf(false) }

    val bubbleColor = if (isSelf) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isSelf) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val metaColor = if (isSelf) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val accent = if (isSelf) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .padding(top = if (newGroup) 7.dp else 0.dp),
            horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
        ) {
            if (showName && !isSelf) {
                Text(
                    text = sender,
                    modifier = Modifier.padding(start = 12.dp, bottom = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = memberColor(sender),
                )
            }
            Surface(shape = RoundedCornerShape(16.dp), color = bubbleColor) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = contentColor.copy(alpha = 0.10f),
                        ) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Text("📄", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                text = group.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = contentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = size(group.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = metaColor,
                            )
                        }
                    }
                    if (preview != null) {
                        FileImagePreview(
                            preview = preview,
                            fileName = group.fileName,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            onClick = { showPreview = true },
                        )
                    }
                    if (group.caption.isNotBlank()) {
                        LinkifiedText(
                            text = group.caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor,
                            linkColor = accent,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    group.rows.forEach { row ->
                        FileReceiverLine(
                            row = row,
                            direction = group.direction,
                            contentColor = contentColor,
                            metaColor = metaColor,
                            accent = accent,
                            onAccept = onAccept,
                            onDecline = onDecline,
                            onCancel = onCancel,
                        )
                    }
                    if (showTimestamps) {
                        Text(
                            text = clock(group.ts, clock24h),
                            modifier = Modifier.align(Alignment.End),
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor,
                        )
                    }
                }
            }
        }
    }

    if (showPreview && preview != null) {
        ImagePreviewDialog(
            preview = preview,
            fileName = group.fileName,
            onDismiss = { showPreview = false },
        )
    }
}

@Composable
private fun FileReceiverLine(
    row: TransferProgress,
    direction: TransferDirection,
    contentColor: Color,
    metaColor: Color,
    accent: Color,
    onAccept: (TransferProgress) -> Unit,
    onDecline: (TransferProgress) -> Unit,
    onCancel: (TransferProgress) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status(row, direction),
                style = MaterialTheme.typography.labelSmall,
                color = if (row.state == TransferState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    metaColor
                },
                modifier = Modifier.weight(1f),
            )
            val actionColors = ButtonDefaults.textButtonColors(contentColor = accent)
            when {
                direction == TransferDirection.INCOMING && row.state == TransferState.OFFERED -> {
                    TextButton(onClick = { onAccept(row) }, colors = actionColors) { Text("Accept") }
                    TextButton(onClick = { onDecline(row) }, colors = actionColors) { Text("Decline") }
                }

                row.state == TransferState.OFFERED || row.isActive ->
                    TextButton(onClick = { onCancel(row) }, colors = actionColors) { Text("Cancel") }

                // The file is in Downloads already; one tap opens it.
                row.state == TransferState.COMPLETED && direction == TransferDirection.INCOMING ->
                    if (row.savedUri?.startsWith("content://") == true) {
                        val context = LocalContext.current
                        TextButton(
                            onClick = { openDownloadedFile(context, row) },
                            colors = actionColors,
                        ) { Text("Open") }
                    }

                else -> Unit
            }
        }
        if (row.isActive && row.size > 0) {
            LinearProgressIndicator(
                progress = { row.fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = accent,
                trackColor = contentColor.copy(alpha = 0.2f),
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

@Composable
private fun memberColor(name: String): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = if (dark) memberColorsDark else memberColorsLight
    return palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
}

private val memberColorsLight = listOf(
    Color(0xFF7B5BB5),
    Color(0xFF2F8A6D),
    Color(0xFFC25A3C),
    Color(0xFF2F6FB3),
    Color(0xFFA8437C),
    Color(0xFF7A6A1F),
)

private val memberColorsDark = listOf(
    Color(0xFFC9B6FF),
    Color(0xFF7FD6B6),
    Color(0xFFFF9D84),
    Color(0xFF8AB8F0),
    Color(0xFFF19AC8),
    Color(0xFFD8C977),
)

private fun sameDay(a: Long, b: Long): Boolean = day(a) == day(b)

private fun day(epochMillis: Long): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun dayLabel(epochMillis: Long): String {
    val date = day(epochMillis)
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(
            DateTimeFormatter.ofPattern(
                if (date.year == today.year) "d MMMM" else "d MMMM yyyy",
                Locale.getDefault(),
            ),
        )
    }
}

private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

private fun clock(epochMillis: Long, clock24h: Boolean): String =
    SimpleDateFormat(if (clock24h) "HH:mm" else "hh:mm a", Locale.getDefault())
        .format(Date(epochMillis))

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GiB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MiB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.1f KiB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
