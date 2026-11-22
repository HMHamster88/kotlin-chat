package org.kotlinchat.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.Hello
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.ProtocolJson
import org.kotlinchat.protocol.payloadOf

/**
 * M0 control channel: connect, send, receive.
 *
 * Auth, rooms and reconnection land in M1; this class is the seam they plug into.
 */
class ChatClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private val http: HttpClient = newHttpClient()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 128)
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var pump: Job? = null
    private var sequence = 0L

    suspend fun connect(host: String, port: Int, name: String): Result<Unit> {
        disconnect()
        val url = "ws://$host:$port/ws"
        _state.value = ConnectionState.Connecting(url)

        return runCatching {
            val opened = http.webSocketSession(url)
            session = opened
            pump = scope.launch { pumpFrames(opened) }
            _state.value = ConnectionState.Connected

            val hello = Envelope.of(
                type = MessageType.HELLO,
                id = nextId(),
                payload = payloadOf(Hello(name = name)),
            )
            check(send(hello)) { "could not send hello" }
        }.onFailure { t ->
            pump?.cancel()
            pump = null
            session = null
            _state.value = ConnectionState.Failed(t.message ?: "connect failed")
        }
    }

    suspend fun send(type: String, payload: JsonObject = JsonObject(emptyMap())): Boolean =
        send(Envelope.of(type = type, id = nextId(), payload = payload))

    suspend fun send(envelope: Envelope): Boolean {
        val current = session ?: return false
        return runCatching {
            current.outgoing.send(Frame.Text(envelope.encode()))
            true
        }.getOrDefault(false)
    }

    fun disconnect() {
        pump?.cancel()
        pump = null
        val current = session
        session = null
        if (current != null) {
            current.cancel()
        }
        _state.value = ConnectionState.Disconnected
    }

    override fun close() {
        disconnect()
        http.close()
    }

    private suspend fun pumpFrames(opened: DefaultClientWebSocketSession) {
        try {
            for (frame in opened.incoming) {
                if (frame !is Frame.Text) continue
                val envelope = runCatching { Envelope.decode(frame.readText()) }.getOrNull() ?: continue
                _incoming.emit(envelope)
            }
            if (session === opened) _state.value = ConnectionState.Disconnected
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (session === opened) _state.value = ConnectionState.Failed(t.message ?: "connection lost")
        }
    }

    private fun nextId(): String = "c-${++sequence}"
}
