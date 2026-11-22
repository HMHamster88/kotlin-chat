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
