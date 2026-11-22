package org.kotlinchat.protocol.crypto

import org.kotlinchat.protocol.KdfParams

/**
 * SCRAM-SHA-256 as specified in docs/PROTOCOL.md §2.
 *
 * Both client and server call into this object so the byte layout can only drift once.
 * Argon2id is the key-derivation function; everything after it is plain HMAC/SHA-256.
 */
object Scram {

    private val CLIENT_KEY_LABEL = "client key".encodeToByteArray()
    private val SERVER_KEY_LABEL = "server key".encodeToByteArray()

    /** Keys derived from the password. The server only ever persists [storedKey] and [serverKey]. */
    class Keys(
        val clientKey: ByteArray,
        val storedKey: ByteArray,
        val serverKey: ByteArray,
    )

    fun deriveAuthKey(password: String, kdf: KdfParams): ByteArray =
        Crypto.argon2id(
            password = password,
            salt = Crypto.base64Decode(kdf.salt),
            memoryKib = kdf.m,
            iterations = kdf.t,
            parallelism = kdf.p,
        )

    fun keys(authKey: ByteArray): Keys {
        val clientKey = Crypto.hmacSha256(authKey, CLIENT_KEY_LABEL)
        return Keys(
            clientKey = clientKey,
            storedKey = Crypto.sha256(clientKey),
            serverKey = Crypto.hmacSha256(authKey, SERVER_KEY_LABEL),
        )
    }

    /**
     * `UTF8(name) 0x00 serverNonce 0x00 clientNonce`
     *
     * The name must be exactly what the client sent in `hello`; the server stores it
     * verbatim for the duration of the handshake.
     */
    fun authMessage(name: String, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        name.encodeToByteArray() +
            byteArrayOf(0) + serverNonce +
            byteArrayOf(0) + clientNonce

    fun clientProof(keys: Keys, authMessage: ByteArray): ByteArray {
        val signature = Crypto.hmacSha256(keys.storedKey, authMessage)
        return xor(keys.clientKey, signature)
    }

    fun verifyProof(proof: ByteArray, storedKey: ByteArray, authMessage: ByteArray): Boolean {
        if (proof.size != storedKey.size) return false
        val signature = Crypto.hmacSha256(storedKey, authMessage)
        val recoveredClientKey = xor(proof, signature)
        return Crypto.constantTimeEquals(Crypto.sha256(recoveredClientKey), storedKey)
    }

    fun serverSignature(serverKey: ByteArray, authMessage: ByteArray): ByteArray =
        Crypto.hmacSha256(serverKey, authMessage)

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "xor operands must be the same length" }
        return ByteArray(a.size) { index -> (a[index].toInt() xor b[index].toInt()).toByte() }
    }
}
