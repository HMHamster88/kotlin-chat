package org.kotlinchat.client

/** Coarse connection state surfaced to the UI. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val endpoint: String) : ConnectionState
    data object Authenticating : ConnectionState
    data object Ready : ConnectionState
    data class Reconnecting(val attempt: Int, val delayMillis: Long, val cause: String?) : ConnectionState

    /** Terminal: bad password, mismatched server fingerprint, protocol refusal. */
    data class Failed(val reason: String) : ConnectionState

    val isReady: Boolean get() = this is Ready

    val label: String
        get() = when (this) {
            Idle -> "not connected"
            is Connecting -> "connecting to $endpoint"
            Authenticating -> "authenticating"
            Ready -> "connected"
            is Reconnecting -> "reconnecting (attempt $attempt in ${delayMillis / 1000}s)"
            is Failed -> "failed: $reason"
        }
}
