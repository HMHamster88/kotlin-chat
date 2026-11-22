package org.kotlinchat.client

/**
 * What the certificate a server presents means for this client, so a UI can ask the user to
 * confirm a key on first contact instead of silently pinning it.
 *
 * A programmatic client may skip the question: [ChatClient.connect] pins on first use, which is
 * the documented trust-on-first-use contract. Interactive clients call
 * [ChatClient.checkServerKey] first and only connect once the user has approved.
 */
sealed interface ServerKeyTrust {

    /** Nothing is pinned for this server yet; the user decides about [fingerprint]. */
    data class FirstSight(val fingerprint: String) : ServerKeyTrust

    /** The presented key matches the pin stored for this server. */
    data class Pinned(val fingerprint: String) : ServerKeyTrust

    /** The server presented a key other than the pinned one. */
    data class Changed(val pinned: String, val presented: String) : ServerKeyTrust

    /** The key a user would be approving by continuing. */
    val approvedFingerprint: String
        get() = when (this) {
            is FirstSight -> fingerprint
            is Pinned -> fingerprint
            is Changed -> presented
        }
}
