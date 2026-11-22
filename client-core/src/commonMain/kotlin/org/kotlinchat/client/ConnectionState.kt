package org.kotlinchat.client

/**
 * A connection problem in the shape a UI can show: what happened, what to try, and the raw
 * exception text for people who want it.
 */
data class ConnectionFailure(
    val summary: String,
    val hint: String? = null,
    val detail: String? = null,
)

/** Coarse connection state surfaced to the UI. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val endpoint: String) : ConnectionState
    data object Authenticating : ConnectionState
    data object Ready : ConnectionState
    data class Reconnecting(
        val attempt: Int,
        val delayMillis: Long,
        val cause: String?,
        val hint: String? = null,
    ) : ConnectionState

    /** Terminal: bad password, mismatched server fingerprint, protocol refusal. */
    data class Failed(val failure: ConnectionFailure) : ConnectionState {
        constructor(reason: String) : this(ConnectionFailure(reason))

        val reason: String get() = failure.summary
    }

    val isReady: Boolean get() = this is Ready

    val label: String
        get() = when (this) {
            Idle -> "not connected"
            is Connecting -> "connecting to $endpoint"
            Authenticating -> "authenticating"
            Ready -> "connected"
            is Reconnecting -> buildString {
                append("reconnecting (attempt $attempt in ${delayMillis / 1000}s)")
                if (!cause.isNullOrBlank()) append(": $cause")
            }
            is Failed -> "failed: ${failure.summary}"
        }
}
