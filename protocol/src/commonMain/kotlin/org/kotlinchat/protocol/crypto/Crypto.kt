package org.kotlinchat.protocol.crypto

/**
 * Platform primitives for the SCRAM handshake.
 *
 * Desktop and Android use JCE + BouncyCastle; an iOS actual will need CommonCrypto
 * and a native Argon2id implementation.
 */
expect object Crypto {
    fun argon2id(
        password: String,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        outLength: Int = 32,
    ): ByteArray

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray

    fun sha256(data: ByteArray): ByteArray

    fun randomBytes(count: Int): ByteArray

    /** Timing-safe comparison; both operands are fixed-length in this protocol. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean

    fun base64Encode(bytes: ByteArray): String

    fun base64Decode(text: String): ByteArray
}

/** Incremental SHA-256 for hashing files while they stream. */
expect class Sha256() {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    fun digest(): ByteArray
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
    }
    return out.toString()
}

/** Formats a certificate fingerprint the way Mumble and browsers show it: `AA:BB:...`. */
fun ByteArray.toFingerprint(): String =
    toHex().chunked(2).joinToString(":").uppercase()

fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it == ':' || it.isWhitespace() }
    require(clean.length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
