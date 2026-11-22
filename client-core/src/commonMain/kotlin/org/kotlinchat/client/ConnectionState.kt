package org.kotlinchat.client

/** Coarse connection state surfaced to the UI. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val endpoint: String) : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val reason: String) : ConnectionState

    val label: String
        get() = when (this) {
            Disconnected -> "disconnected"
            is Connecting -> "connecting to $endpoint"
            Connected -> "connected"
            is Failed -> "failed: $reason"
        }
}
