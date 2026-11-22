package org.kotlinchat.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.toFingerprint
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

        (context.socketFactory.createSocket(host, port) as SSLSocket).use { socket ->
            socket.startHandshake()
            val certificate = socket.session.peerCertificates.first() as X509Certificate
            Crypto.sha256(certificate.encoded).toFingerprint()
        }
    }

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
}
