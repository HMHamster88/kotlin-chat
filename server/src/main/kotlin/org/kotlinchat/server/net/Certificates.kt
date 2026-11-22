package org.kotlinchat.server.net

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.toFingerprint
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

/**
 * Self-signed TLS material. The certificate is only a container for the server's key:
 * clients authenticate it by pinned SHA-256 fingerprint, not by a CA chain.
 */
object Certificates {

    private const val KEY_ALIAS_PASSWORD_FALLBACK = "kotlin-chat"

    fun createKeyStore(
        alias: String,
        password: CharArray,
        commonName: String,
        subjectAltNames: List<String>,
        validDays: Long = 3650,
    ): KeyStore {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        val subject = X500Name("CN=$commonName")
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Date.from(now.minus(1, ChronoUnit.DAYS)),
            Date.from(now.plus(validDays, ChronoUnit.DAYS)),
            subject,
            keyPair.public,
        )

        val names = subjectAltNames.distinct().map { name ->
            val tag = if (name.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
                GeneralName.iPAddress
            } else {
                GeneralName.dNSName
            }
            GeneralName(tag, name)
        }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(alias, keyPair.private, password, arrayOf(certificate))
        }
    }

    fun loadKeyStore(path: Path, password: CharArray): KeyStore {
        require(Files.exists(path)) { "keystore not found: $path (run `init` first)" }
        return KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(path).use { load(it, password) }
        }
    }

    fun store(keyStore: KeyStore, path: Path, password: CharArray) {
        path.toAbsolutePath().parent?.createDirectories()
        path.outputStream().use { keyStore.store(it, password) }
    }

    fun certificate(keyStore: KeyStore, alias: String): X509Certificate =
        keyStore.getCertificate(alias) as? X509Certificate
            ?: error("keystore has no certificate under alias '$alias'")

    fun fingerprint(keyStore: KeyStore, alias: String): String =
        Crypto.sha256(certificate(keyStore, alias).encoded).toFingerprint()

    /** Every alias that has a key entry; `init` writes exactly one. */
    fun firstAlias(keyStore: KeyStore): String =
        keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) }
            ?: error("keystore contains no key entries")

    fun defaultPasswordAliasHint(): String = KEY_ALIAS_PASSWORD_FALLBACK
}
