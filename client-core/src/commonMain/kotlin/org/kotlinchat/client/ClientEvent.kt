package org.kotlinchat.client

import org.kotlinchat.protocol.ErrorCode

/** One-off things worth showing as a toast or banner. */
sealed interface ClientEvent {
    data class Notice(val message: String) : ClientEvent
    data class Error(val code: ErrorCode?, val message: String) : ClientEvent
}
