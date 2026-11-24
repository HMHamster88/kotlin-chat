package org.kotlinchat.client.file

/** Magic prefix for a preview message on the peer-to-peer file data channel. */
private val PREVIEW_MAGIC = "KCPREVIEW\u0001\r\n".encodeToByteArray()

private const val MAX_TRANSFER_ID_BYTES = 64
private const val HEADER_BYTES = 1 + 4 // id length + payload length

internal const val MAX_PREVIEW_BYTES = 96 * 1024

internal data class PreviewFrame(
    val transferId: String,
    val payload: ByteArray,
)

/**
 * Encodes a thumbnail as a standalone data-channel message. File chunks stay raw, so older
 * clients simply ignore previews: they have no matching accepted transfer to attribute them to.
 */
internal fun encodePreviewFrame(transferId: String, payload: ByteArray): ByteArray? {
    val id = transferId.encodeToByteArray()
    if (id.isEmpty() || id.size > MAX_TRANSFER_ID_BYTES) return null
    if (payload.isEmpty() || payload.size > MAX_PREVIEW_BYTES) return null

    val out = ByteArray(PREVIEW_MAGIC.size + HEADER_BYTES + id.size + payload.size)
    PREVIEW_MAGIC.copyInto(out, 0)
    var offset = PREVIEW_MAGIC.size
    out[offset++] = id.size.toByte()
    writeInt(out, offset, payload.size)
    offset += 4
    id.copyInto(out, offset)
    offset += id.size
    payload.copyInto(out, offset)
    return out
}

/** Returns null for a normal file chunk or a malformed frame. */
internal fun decodePreviewFrame(bytes: ByteArray): PreviewFrame? {
    if (bytes.size < PREVIEW_MAGIC.size + HEADER_BYTES) return null
    for (index in PREVIEW_MAGIC.indices) {
        if (bytes[index] != PREVIEW_MAGIC[index]) return null
    }

    var offset = PREVIEW_MAGIC.size
    val idLength = bytes[offset++].toInt() and 0xff
    if (idLength !in 1..MAX_TRANSFER_ID_BYTES) return null
    val payloadLength = readInt(bytes, offset)
    offset += 4
    if (payloadLength == 0 || payloadLength > MAX_PREVIEW_BYTES) return null
    if (bytes.size != offset + idLength + payloadLength) return null

    val transferId = runCatching {
        bytes.copyOfRange(offset, offset + idLength).decodeToString()
    }.getOrNull() ?: return null
    offset += idLength
    return PreviewFrame(transferId, bytes.copyOfRange(offset, offset + payloadLength))
}

private fun writeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

private fun readInt(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)
