package org.kotlinchat.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.engine.cio.CIO
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.hexToBytes
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

internal actual fun newHttpClient(pin: String?, serverName: String?): HttpClient = HttpClient(CIO) {
    install(WebSockets) {
        pingIntervalMillis = 20_000
    }
    if (pin != null) {
        engine {
            https {
                trustManager = pinnedTrustManager(pin)
                // Without this the engine fills the name in from the resolved address, and a
                // LAN IP reverse-resolves to a machine name the certificate cannot contain.
                this.serverName = serverName
            }
        }
    }
}

/**
 * Trusts exactly one certificate, identified by its SHA-256 fingerprint.
 *
 * Hostname verification is intentionally absent: a self-hosted server rarely has a
 * matching SAN, and the pinned key is the stronger statement anyway.
 */
internal fun pinnedTrustManager(fingerprint: String): X509TrustManager {
    val expected = fingerprint.hexToBytes()

    return object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("this client does not accept client certificates")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw CertificateException("server presented no certificate")
            val actual = Crypto.sha256(certificate.encoded)
            if (!MessageDigest.isEqual(actual, expected)) {
                throw CertificateException(
                    "server certificate does not match the pinned fingerprint " +
                        "(expected ${fingerprint}, got ${actual.toFingerprint()})",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

private fun ByteArray.toFingerprint(): String {
    val hex = "0123456789abcdef"
    return joinToString(":") { byte ->
        val value = byte.toInt() and 0xff
        "${hex[value ushr 4]}${hex[value and 0x0f]}"
    }.uppercase()
}
