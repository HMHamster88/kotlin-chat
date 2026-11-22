package org.kotlinchat.server.core

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.Member

/** One connected transport, before and after authentication. */
class ClientSession(
    val id: String,
    val remoteHost: String,
    val outgoing: Channel<String> = Channel(capacity = 64),
) {
    @Volatile
    var authenticated: Boolean = false
    var name: String = ""
    var pubkeyFp: String? = null
    var roomId: String = LOBBY
    var joinedAt: Long = 0
    var selfMute: Boolean = false
    var selfDeaf: Boolean = false
    var sharing: Boolean = false

    val chatLimiter = TokenBucket(ratePerSecond = 5.0, burst = 10)
    val signalLimiter = TokenBucket(ratePerSecond = 50.0, burst = 100)
    val roomChangeLimiter = TokenBucket(ratePerSecond = 2.0, burst = 5)

    fun member(): Member = Member(
        sessionId = id,
        name = name,
        pubkeyFp = pubkeyFp,
        joinedAt = joinedAt,
        selfMute = selfMute,
        selfDeaf = selfDeaf,
        sharing = sharing,
    )

    /** Non-blocking; returns false when the outbound buffer is gone or full. */
    fun send(text: String): Boolean = outgoing.trySend(text).isSuccess

    fun send(type: String, payload: JsonObject, replyTo: String? = null): Boolean =
        send(Envelope(type = type, replyTo = replyTo, payload = payload).encode())

    companion object {
        const val LOBBY = "lobby"
    }
}
