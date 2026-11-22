package org.kotlinchat.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.file.TransferDirection
import org.kotlinchat.client.file.TransferProgress
import org.kotlinchat.client.file.TransferState

@Composable
fun TransfersPanel(
    transfers: List<TransferProgress>,
    onAccept: (TransferProgress) -> Unit,
    onDecline: (TransferProgress) -> Unit,
    onCancel: (TransferProgress) -> Unit,
) {
    val visible = transfers
        .filter { it.isActive || it.state == TransferState.OFFERED || it.state == TransferState.COMPLETED || it.state == TransferState.FAILED }
        .takeLast(4)
    if (visible.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visible.forEach { transfer ->
            TransferRow(transfer, onAccept, onDecline, onCancel)
        }
    }
}

@Composable
private fun TransferRow(
    transfer: TransferProgress,
    onAccept: (TransferProgress) -> Unit,
    onDecline: (TransferProgress) -> Unit,
    onCancel: (TransferProgress) -> Unit,
) {
    val arrow = if (transfer.direction == TransferDirection.INCOMING) "↓" else "↑"
    val peer = if (transfer.peerName.isBlank()) "room" else transfer.peerName

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = arrow,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = transfer.fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = describe(transfer, peer),
                style = MaterialTheme.typography.labelSmall,
                color = if (transfer.state == TransferState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (transfer.isActive && transfer.size > 0) {
                LinearProgressIndicator(
                    progress = { transfer.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp),
                )
            }
        }

        when (transfer.state) {
            TransferState.OFFERED -> if (transfer.direction == TransferDirection.INCOMING) {
                TextButton(onClick = { onAccept(transfer) }) { Text("Accept") }
                TextButton(onClick = { onDecline(transfer) }) { Text("Decline") }
            } else {
                TextButton(onClick = { onCancel(transfer) }) { Text("Cancel") }
            }

            TransferState.ACCEPTED, TransferState.ACTIVE ->
                TextButton(onClick = { onCancel(transfer) }) { Text("Cancel") }

            else -> Unit
        }
    }
}

private fun describe(transfer: TransferProgress, peer: String): String = when (transfer.state) {
    TransferState.OFFERED ->
        if (transfer.direction == TransferDirection.INCOMING) {
            "$peer wants to send ${size(transfer.size)}"
        } else {
            "waiting for someone to accept ${size(transfer.size)}"
        }

    TransferState.ACCEPTED, TransferState.ACTIVE ->
        "${size(transfer.bytes)} / ${size(transfer.size)} ${if (transfer.direction == TransferDirection.INCOMING) "from $peer" else "to $peer"}"

    TransferState.COMPLETED ->
        transfer.savedPath?.let { "saved to $it" }
            ?: "sent to $peer (${size(transfer.size)})"

    TransferState.DECLINED -> "declined${transfer.error?.let { " · $it" } ?: ""}"
    TransferState.CANCELLED -> "cancelled"
    TransferState.FAILED -> "failed${transfer.error?.let { " · $it" } ?: ""}"
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GiB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MiB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.1f KiB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
