package org.kotlinchat.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.hexToBytes
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * OkHttp rather than Ktor's CIO engine: CIO carries its own TLS implementation, which dropped
 * handshakes to a remote server about half the time ("Negotiation failed due to EOS" / "Not
 * enough data available") while JSSE, curl and openssl were reliable against the same host.
 * OkHttp delegates TLS to the platform (JSSE on the JVM, Conscrypt on Android).
 *
 * [serverName] is unused here because OkHttp derives SNI from the URL and, with the pinned
 * trust manager in place, the certificate is matched by fingerprint rather than by hostname.
 */
@Suppress("UNUSED_PARAMETER")
internal actual fun newHttpClient(pin: String?, serverName: String?): HttpClient = HttpClient(OkHttp) {
    install(WebSockets) {
        pingIntervalMillis = 20_000
    }
    engine {
        config {
            // The session is one long-lived websocket, so an idle read is not a failure.
            readTimeout(0, TimeUnit.MILLISECONDS)
            connectTimeout(15, TimeUnit.SECONDS)
            retryOnConnectionFailure(false)
            if (pin != null) {
                val trustManager = pinnedTrustManager(pin)
                sslSocketFactory(
                    SSLContext.getInstance("TLS")
                        .apply { init(null, arrayOf<TrustManager>(trustManager), null) }
                        .socketFactory,
                    trustManager,
                )
                // The pin is the identity; a self-hosted certificate rarely names the host.
                hostnameVerifier { _, _ -> true }
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
