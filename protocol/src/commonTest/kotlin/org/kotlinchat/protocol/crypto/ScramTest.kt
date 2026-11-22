package org.kotlinchat.protocol.crypto

import org.kotlinchat.protocol.KdfParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScramTest {

    // Cheap KDF parameters: the arithmetic is identical, only the work factor differs.
    private val kdf = KdfParams(
        salt = Crypto.base64Encode(ByteArray(16) { (it * 7).toByte() }),
        m = 16_384,
        t = 1,
        p = 1,
    )

    private val serverNonce = ByteArray(16) { (it + 1).toByte() }
    private val clientNonce = ByteArray(16) { (it + 101).toByte() }

    @Test
    fun `derivation is deterministic`() {
        val first = Scram.deriveAuthKey("correct horse", kdf)
        val second = Scram.deriveAuthKey("correct horse", kdf)
        assertTrue(first.contentEquals(second))
        assertEquals(32, first.size)
    }

    @Test
    fun `different passwords derive different keys`() {
        val a = Scram.deriveAuthKey("correct horse", kdf)
        val b = Scram.deriveAuthKey("correct horse ", kdf)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `client proof verifies against the stored key`() {
        val keys = Scram.keys(Scram.deriveAuthKey("s3cret", kdf))
        val message = Scram.authMessage("alice", serverNonce, clientNonce)
        val proof = Scram.clientProof(keys, message)

        assertTrue(Scram.verifyProof(proof, keys.storedKey, message))
        assertEquals(32, proof.size)
        assertNotEquals(keys.clientKey.toList(), proof.toList(), "proof must not be the client key")
    }

    @Test
    fun `wrong password is rejected`() {
        val verifier = Scram.keys(Scram.deriveAuthKey("s3cret", kdf))
        val attacker = Scram.keys(Scram.deriveAuthKey("s3cret!", kdf))
        val message = Scram.authMessage("alice", serverNonce, clientNonce)

        assertFalse(Scram.verifyProof(Scram.clientProof(attacker, message), verifier.storedKey, message))
    }

    @Test
    fun `proof is bound to the auth message`() {
        val keys = Scram.keys(Scram.deriveAuthKey("s3cret", kdf))
        val message = Scram.authMessage("alice", serverNonce, clientNonce)
        val otherMessage = Scram.authMessage("alice", serverNonce, clientNonce.copyOf().also { it[0]++ })
        val proof = Scram.clientProof(keys, message)

        assertFalse(Scram.verifyProof(proof, keys.storedKey, otherMessage))
    }

    @Test
    fun `server signature matches on both sides`() {
        val verifier = Scram.keys(Scram.deriveAuthKey("s3cret", kdf))
        val handshakeKeys = Scram.keys(Scram.deriveAuthKey("s3cret", kdf))
        val message = Scram.authMessage("alice", serverNonce, clientNonce)

        assertTrue(
            Scram.serverSignature(verifier.serverKey, message)
                .contentEquals(Scram.serverSignature(handshakeKeys.serverKey, message)),
        )
    }
}
