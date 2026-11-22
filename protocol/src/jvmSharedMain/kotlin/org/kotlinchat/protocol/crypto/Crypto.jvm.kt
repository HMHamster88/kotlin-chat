package org.kotlinchat.protocol.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual object Crypto {

    private val random = SecureRandom()

    actual fun argon2id(
        password: String,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        outLength: Int,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(parameters)

        val output = ByteArray(outLength)
        generator.generateBytes(password.toCharArray(), output)
        return output
    }

    actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    actual fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    actual fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also(random::nextBytes)

    actual fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    actual fun base64Encode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    actual fun base64Decode(text: String): ByteArray =
        Base64.getDecoder().decode(text)
}

actual class Sha256 actual constructor() {
    private val digest = MessageDigest.getInstance("SHA-256")

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }

    actual fun digest(): ByteArray = digest.digest()
}
