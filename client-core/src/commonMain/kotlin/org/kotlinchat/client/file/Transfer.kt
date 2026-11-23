package org.kotlinchat.client.file

enum class TransferDirection { INCOMING, OUTGOING }

enum class TransferState { OFFERED, ACCEPTED, ACTIVE, COMPLETED, DECLINED, CANCELLED, FAILED }

/** UI-facing state of one file transfer. Bytes never touch the server. */
data class TransferProgress(
    val id: String,
    val peerId: String,
    val peerName: String,
    val fileName: String,
    val size: Long,
    val bytes: Long = 0,
    val direction: TransferDirection,
    val state: TransferState,
    val sha256: String = "",
    val savedPath: String? = null,
    val error: String? = null,
    /** When the offer was made; keeps a file in place while its receivers accept it. */
    val ts: Long = 0L,
    /** Stable identity for list keys: id alone repeats across receivers. */
    val key: String = "$direction:$id:$peerId",
) {
    val fraction: Float
        get() = if (size <= 0L) 0f else (bytes.toDouble() / size).toFloat().coerceIn(0f, 1f)

    val isActive: Boolean get() = state == TransferState.ACTIVE || state == TransferState.ACCEPTED
    val isFinished: Boolean
        get() = state == TransferState.COMPLETED ||
            state == TransferState.DECLINED ||
            state == TransferState.CANCELLED ||
            state == TransferState.FAILED
}

/**
 * One chat entry per file: an outgoing offer is a single message even when several peers accept
 * it, so [rows] holds one row per receiver (or the bare offer while nobody has accepted).
 */
data class TransferGroup(
    val id: String,
    val direction: TransferDirection,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val ts: Long,
    val rows: List<TransferProgress>,
) {
    /** The peer a one-to-one transfer belongs to, for accepting or declining it. */
    val peer: TransferProgress get() = rows.first()
}

/** Groups the flat transfer rows into chat entries, newest last. */
fun List<TransferProgress>.groupedForChat(): List<TransferGroup> =
    groupBy { it.direction to it.id }
        .map { (key, rows) ->
            val ordered = rows.sortedBy { it.ts }
            val first = ordered.first()
            TransferGroup(
                id = key.second,
                direction = key.first,
                fileName = first.fileName,
                size = first.size,
                sha256 = first.sha256,
                ts = ordered.minOf { it.ts },
                rows = ordered,
            )
        }
        .sortedBy { it.ts }
