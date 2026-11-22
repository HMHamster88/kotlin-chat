package org.kotlinchat.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.toFingerprint
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createDirectories

internal actual suspend fun probeTlsFingerprint(host: String, port: Int): String =
    withContext(Dispatchers.IO) {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            },
        )
        val context = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }

        // A dead host must not stall the connect screen; the probe is also what turns an
        // unreachable server into a quick, clear failure.
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { socket ->
                socket.soTimeout = CONNECT_TIMEOUT_MS
                socket.startHandshake()
                val certificate = socket.session.peerCertificates.first() as X509Certificate
                Crypto.sha256(certificate.encoded).toFingerprint()
            }
        } finally {
            runCatching { raw.close() }
        }
    }

private const val CONNECT_TIMEOUT_MS = 10_000

/** Pinned fingerprints stored as a properties file; the location differs per platform. */
internal class FilePinStore(private val path: Path) : PinStore {

    override fun get(hostKey: String): String? = synchronized(this) {
        if (!Files.exists(path)) return null
        read().getProperty(hostKey)
    }

    override fun put(hostKey: String, fingerprint: String) {
        synchronized(this) {
            val properties = read()
            properties.setProperty(hostKey, fingerprint)
            path.toAbsolutePath().parent?.createDirectories()
            Files.newOutputStream(path).use { properties.store(it, "kotlin-chat pinned server fingerprints") }
        }
    }

    override fun remove(hostKey: String) {
        synchronized(this) {
            if (!Files.exists(path)) return
            val properties = read()
            if (properties.remove(hostKey) == null) return
            path.toAbsolutePath().parent?.createDirectories()
            Files.newOutputStream(path).use { properties.store(it, "kotlin-chat pinned server fingerprints") }
        }
    }

    private fun read(): Properties = Properties().apply {
        if (Files.exists(path)) Files.newInputStream(path).use { load(it) }
    }
}

internal class MemoryPinStore : PinStore {
    private val values = mutableMapOf<String, String>()

    override fun get(hostKey: String): String? = synchronized(this) { values[hostKey] }

    override fun put(hostKey: String, fingerprint: String) {
        synchronized(this) { values[hostKey] = fingerprint }
    }

    override fun remove(hostKey: String) {
        synchronized(this) { values.remove(hostKey) }
    }
}
