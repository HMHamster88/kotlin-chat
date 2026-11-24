package org.kotlinchat.client.control

/** Wire format for the dedicated control channel. */
internal sealed interface ControlFrame {
    data object Request : ControlFrame
    data class Grant(val leaseSeconds: Int) : ControlFrame
    data class Deny(val reason: String) : ControlFrame
    data class Revoke(val reason: String) : ControlFrame
    data object Cancel : ControlFrame
    data object Heartbeat : ControlFrame
    data class Move(val x: Int, val y: Int) : ControlFrame
    data class Button(val button: Int, val down: Boolean) : ControlFrame
    data class Wheel(val delta: Int, val horizontal: Boolean) : ControlFrame
    data class Key(val keyCode: Int, val down: Boolean) : ControlFrame
}

private val CONTROL_MAGIC = byteArrayOf('K'.code.toByte(), 'C'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte())
private const val CONTROL_VERSION = 1
private const val HEADER_BYTES = 6
private const val MAX_CONTROL_BYTES = 512
private const val MAX_REASON_BYTES = 200
private const val TYPE_REQUEST = 1
private const val TYPE_GRANT = 2
private const val TYPE_DENY = 3
private const val TYPE_REVOKE = 4
private const val TYPE_CANCEL = 5
private const val TYPE_HEARTBEAT = 6
private const val TYPE_MOVE = 7
private const val TYPE_BUTTON = 8
private const val TYPE_WHEEL = 9
private const val TYPE_KEY = 10

internal fun encodeControlFrame(frame: ControlFrame): ByteArray {
    val payload = when (frame) {
        ControlFrame.Request -> ByteArray(0)
        is ControlFrame.Grant -> byteArrayOf(
            (frame.leaseSeconds ushr 24).toByte(),
            (frame.leaseSeconds ushr 16).toByte(),
            (frame.leaseSeconds ushr 8).toByte(),
            frame.leaseSeconds.toByte(),
        )
        is ControlFrame.Deny -> encodeReason(frame.reason)
        is ControlFrame.Revoke -> encodeReason(frame.reason)
        ControlFrame.Cancel -> ByteArray(0)
        ControlFrame.Heartbeat -> ByteArray(0)
        is ControlFrame.Move -> byteArrayOf(
            (frame.x ushr 8).toByte(), frame.x.toByte(),
            (frame.y ushr 8).toByte(), frame.y.toByte(),
        )
        is ControlFrame.Button -> byteArrayOf(frame.button.toByte(), if (frame.down) 1 else 0)
        is ControlFrame.Wheel -> byteArrayOf(
            (frame.delta ushr 8).toByte(), frame.delta.toByte(),
            if (frame.horizontal) 1 else 0,
        )
        is ControlFrame.Key -> byteArrayOf(
            (frame.keyCode ushr 8).toByte(), frame.keyCode.toByte(),
            if (frame.down) 1 else 0,
        )
    }
    val type = when (frame) {
        ControlFrame.Request -> TYPE_REQUEST
        is ControlFrame.Grant -> TYPE_GRANT
        is ControlFrame.Deny -> TYPE_DENY
        is ControlFrame.Revoke -> TYPE_REVOKE
        ControlFrame.Cancel -> TYPE_CANCEL
        ControlFrame.Heartbeat -> TYPE_HEARTBEAT
        is ControlFrame.Move -> TYPE_MOVE
        is ControlFrame.Button -> TYPE_BUTTON
        is ControlFrame.Wheel -> TYPE_WHEEL
        is ControlFrame.Key -> TYPE_KEY
    }
    val out = ByteArray(HEADER_BYTES + payload.size)
    CONTROL_MAGIC.copyInto(out, 0)
    out[4] = CONTROL_VERSION.toByte()
    out[5] = type.toByte()
    payload.copyInto(out, HEADER_BYTES)
    return out
}

internal fun decodeControlFrame(bytes: ByteArray): ControlFrame? {
    if (bytes.size < HEADER_BYTES || bytes.size > MAX_CONTROL_BYTES) return null
    for (index in CONTROL_MAGIC.indices) {
        if (bytes[index] != CONTROL_MAGIC[index]) return null
    }
    if (bytes[4].toInt() and 0xff != CONTROL_VERSION) return null
    val payload = bytes.size - HEADER_BYTES
    return when (bytes[5].toInt() and 0xff) {
        TYPE_REQUEST -> if (payload == 0) ControlFrame.Request else null
        TYPE_GRANT -> if (payload == 4) ControlFrame.Grant(readInt(bytes, HEADER_BYTES)) else null
        TYPE_DENY -> decodeReason(bytes)?.let { ControlFrame.Deny(it) }
        TYPE_REVOKE -> decodeReason(bytes)?.let { ControlFrame.Revoke(it) }
        TYPE_CANCEL -> if (payload == 0) ControlFrame.Cancel else null
        TYPE_HEARTBEAT -> if (payload == 0) ControlFrame.Heartbeat else null
        TYPE_MOVE -> if (payload == 4) ControlFrame.Move(
            readUShort(bytes, HEADER_BYTES),
            readUShort(bytes, HEADER_BYTES + 2),
        ) else null
        TYPE_BUTTON -> if (payload == 2) ControlFrame.Button(
            bytes[HEADER_BYTES].toInt() and 0xff,
            bytes[HEADER_BYTES + 1].toInt() != 0,
        ) else null
        TYPE_WHEEL -> if (payload == 3) ControlFrame.Wheel(
            readShort(bytes, HEADER_BYTES),
            bytes[HEADER_BYTES + 2].toInt() != 0,
        ) else null
        TYPE_KEY -> if (payload == 3) ControlFrame.Key(
            readUShort(bytes, HEADER_BYTES),
            bytes[HEADER_BYTES + 2].toInt() != 0,
        ) else null
        else -> null
    }
}

private fun encodeReason(reason: String): ByteArray {
    val all = reason.encodeToByteArray()
    val text = if (all.size <= MAX_REASON_BYTES) all else all.copyOf(MAX_REASON_BYTES)
    return ByteArray(1 + text.size).also { out ->
        out[0] = text.size.toByte()
        text.copyInto(out, 1)
    }
}

private fun decodeReason(bytes: ByteArray): String? {
    if (bytes.size <= HEADER_BYTES) return null
    val length = bytes[HEADER_BYTES].toInt() and 0xff
    if (bytes.size != HEADER_BYTES + 1 + length) return null
    return runCatching {
        bytes.copyOfRange(HEADER_BYTES + 1, bytes.size).decodeToString()
    }.getOrNull()
}

private fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)

private fun readUShort(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

private fun readShort(bytes: ByteArray, offset: Int): Int {
    val value = readUShort(bytes, offset)
    return if (value and 0x8000 != 0) value - 0x10000 else value
}
