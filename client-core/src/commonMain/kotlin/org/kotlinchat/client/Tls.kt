package org.kotlinchat.client

/** Remembers pinned server fingerprints (trust on first use). */
interface PinStore {
    fun get(hostKey: String): String?
    fun put(hostKey: String, fingerprint: String)
}

internal expect fun platformPinStore(): PinStore

/**
 * Reads the certificate a TLS server presents, without validating it.
 *
 * Only used once, on first contact: the fingerprint is shown to the user and stored, and
 * every later connection verifies against the stored value.
 */
internal expect suspend fun probeTlsFingerprint(host: String, port: Int): String
