package org.kotlinchat.client.file

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.kotlinchat.client.rtc.PeerDataChannel
import org.kotlinchat.protocol.FileAccept
import org.kotlinchat.protocol.FileAcceptRelay
import org.kotlinchat.protocol.FileCancel
import org.kotlinchat.protocol.FileCancelRelay
import org.kotlinchat.protocol.FileDecline
import org.kotlinchat.protocol.FileDeclineRelay
import org.kotlinchat.protocol.FileOffer
import org.kotlinchat.protocol.FileOfferRelay
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.crypto.Crypto
import org.kotlinchat.protocol.crypto.Sha256
import org.kotlinchat.protocol.crypto.toHex
import org.kotlinchat.protocol.payloadOf

/**
 * Coordinates peer-to-peer file transfers.
 *
 * Metadata (offer/accept/decline/cancel) travels over the control channel; bytes travel only
 * over the per-peer data channel. Nothing is stored on the server and no transfer is resumed:
 * a reconnect cancels whatever was in flight.
 */
class FileTransferController(
    private val scope: CoroutineScope,
    private val send: suspend (String, JsonObject) -> Unit,
    private val memberName: (String) -> String,
    private val downloadDirectory: () -> String?,
) {

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    private val lock = Any()
    private val offers = mutableMapOf<String, OutgoingOffer>()
    private val incoming = mutableMapOf<String, IncomingTransfer>()
    private val sendJobs = mutableMapOf<String, Job>()
    private val channelJobs = mutableMapOf<String, Job>()

    @Volatile
    private var channels: Map<String, PeerDataChannel> = emptyMap()

    private var downloadDirectoryOverride: String? = null

    fun setDownloadDirectory(path: String?) {
        downloadDirectoryOverride = path
    }

    // ------------------------------------------------------------------ outgoing

    suspend fun offerFile(path: String) {
        // Opening a picked document (a content:// URI on Android) touches the disk, so keep it
        // off the caller's dispatcher, which is the UI thread in every app.
        val source = withContext(Dispatchers.IO) { openFileSource(path) }
        val offer = try {
            val hash = Sha256()
            var size = 0L
            while (true) {
                val chunk = source.read(CHUNK_BYTES) ?: break
                hash.update(chunk)
                size += chunk.size
            }
            if (size == 0L) error("cannot offer an empty file")
            OutgoingOffer(
                id = "f_" + Crypto.randomBytes(8).toHex(),
                path = path,
                name = source.name,
                size = size,
                sha256 = hash.digest().toHex(),
            )
        } finally {
            source.close()
        }

        synchronized(lock) { offers[offer.id] = offer }
        upsert(
            TransferProgress(
                id = offer.id,
                peerId = "",
                peerName = "room",
                fileName = offer.name,
                size = offer.size,
                direction = TransferDirection.OUTGOING,
                state = TransferState.OFFERED,
                sha256 = offer.sha256,
            ).withKey(),
        )
        send(
            MessageType.FILE_OFFER,
            payloadOf(FileOffer(offer.id, offer.name, offer.size, offer.sha256)),
        )
    }

    fun onAccept(relay: FileAcceptRelay) {
        val offer = synchronized(lock) { offers[relay.transferId] } ?: return
        val key = key(TransferDirection.OUTGOING, offer.id, relay.from)
        if (synchronized(lock) { sendJobs.containsKey(key) }) return

        // One upload per receiver, so each accepted peer gets its own row.
        upsert(
            TransferProgress(
                id = offer.id,
                peerId = relay.from,
                peerName = memberName(relay.from),
                fileName = offer.name,
                size = offer.size,
                direction = TransferDirection.OUTGOING,
                state = TransferState.ACTIVE,
                sha256 = offer.sha256,
            ).withKey(),
        )

        val job = scope.launch { sendFile(offer, relay.from, key) }
        synchronized(lock) { sendJobs[key] = job }

        // The placeholder row only meant "waiting for someone to accept".
        _transfers.update { list -> list.filterNot { it.key == key(TransferDirection.OUTGOING, offer.id, "") } }
    }

    fun onDecline(relay: FileDeclineRelay) {
        val key = key(TransferDirection.OUTGOING, relay.transferId, relay.from)
        update(key) { it.copy(state = TransferState.DECLINED, error = relay.reason) }
    }

    // ------------------------------------------------------------------ incoming

    fun onOffer(relay: FileOfferRelay) {
        upsert(
            TransferProgress(
                id = relay.transferId,
                peerId = relay.from,
                peerName = memberName(relay.from),
                fileName = relay.name,
                size = relay.size,
                direction = TransferDirection.INCOMING,
                state = TransferState.OFFERED,
                sha256 = relay.sha256,
            ).withKey(),
        )
    }

    suspend fun accept(id: String, peerId: String) {
        val key = key(TransferDirection.INCOMING, id, peerId)
        val row = _transfers.value.firstOrNull { it.key == key && it.state == TransferState.OFFERED } ?: return

        val sink = try {
            withContext(Dispatchers.IO) {
                openFileSink(row.fileName, downloadDirectoryOverride ?: downloadDirectory())
            }
        } catch (t: Throwable) {
            update(key) { it.copy(state = TransferState.FAILED, error = t.message ?: "cannot open destination") }
            return
        }

        synchronized(lock) {
            incoming[key] = IncomingTransfer(
                key = key,
                peerId = peerId,
                sink = sink,
                hash = Sha256(),
                size = row.size,
                expectedSha = row.sha256,
            )
        }
        update(key) { it.copy(state = TransferState.ACTIVE) }
        send(MessageType.FILE_ACCEPT, payloadOf(FileAccept(id, to = peerId)))
    }

    suspend fun decline(id: String, peerId: String) {
        val key = key(TransferDirection.INCOMING, id, peerId)
        update(key) { it.copy(state = TransferState.DECLINED) }
        send(MessageType.FILE_DECLINE, payloadOf(FileDecline(id, to = peerId, reason = "declined")))
    }

    suspend fun cancel(id: String, peerId: String) {
        abortLocally(id, peerId)
        update(key(TransferDirection.INCOMING, id, peerId)) { it.copy(state = TransferState.CANCELLED) }
        update(key(TransferDirection.OUTGOING, id, peerId)) { it.copy(state = TransferState.CANCELLED) }
        synchronized(lock) { offers.remove(id) }
        send(
            MessageType.FILE_CANCEL,
            payloadOf(FileCancel(id, to = peerId.ifBlank { null })),
        )
    }

    fun onCancel(relay: FileCancelRelay) {
        abortLocally(relay.transferId, relay.from)
        update(key(TransferDirection.INCOMING, relay.transferId, relay.from)) {
            it.copy(state = TransferState.CANCELLED)
        }
        update(key(TransferDirection.OUTGOING, relay.transferId, relay.from)) {
            it.copy(state = TransferState.CANCELLED)
        }
        synchronized(lock) { offers.remove(relay.transferId) }
    }

    // ------------------------------------------------------------------ transport

    fun bindChannels(available: Map<String, PeerDataChannel>) {
        channels = available
        val known = synchronized(lock) { channelJobs.keys.toSet() }

        (known - available.keys).forEach { peerId ->
            val job = synchronized(lock) { channelJobs.remove(peerId) }
            job?.cancel()
            abortPeerIncoming(peerId)
        }

        (available.keys - known).forEach { peerId ->
            val channel = available.getValue(peerId)
            val job = scope.launch {
                channel.incoming.collect { bytes -> onChunk(peerId, bytes) }
            }
            synchronized(lock) { channelJobs[peerId] = job }
        }
    }

    /** Cancels everything still moving; completed rows are kept for the UI. */
    fun abortActive() {
        synchronized(lock) {
            sendJobs.values.forEach { it.cancel() }
            sendJobs.clear()
            incoming.values.forEach { state -> launchAbort(state) }
            incoming.clear()
        }
        _transfers.update { list ->
            list.map {
                if (it.isActive || it.state == TransferState.OFFERED) {
                    it.copy(state = TransferState.CANCELLED, error = "connection closed")
                } else {
                    it
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            sendJobs.values.forEach { it.cancel() }
            sendJobs.clear()
            channelJobs.values.forEach { it.cancel() }
            channelJobs.clear()
            incoming.values.forEach { state -> launchAbort(state) }
            incoming.clear()
            offers.clear()
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun sendFile(offer: OutgoingOffer, peerId: String, key: String) {
        val channel = waitForChannel(peerId)
        if (channel == null) {
            fail(key, "peer has no file channel")
            return
        }

        var sent = 0L
        var lastUpdate = 0L
        try {
            val source = withContext(Dispatchers.IO) { openFileSource(offer.path) }
            try {
                while (true) {
                    val chunk = source.read(CHUNK_BYTES) ?: break
                    if (!channel.send(chunk)) {
                        fail(key, "transfer interrupted")
                        return
                    }
                    sent += chunk.size
                    val now = System.nanoTime()
                    if (now - lastUpdate > PROGRESS_INTERVAL_NANOS || sent == offer.size) {
                        lastUpdate = now
                        update(key) { it.copy(bytes = sent) }
                    }
                }
            } finally {
                source.close()
            }
            update(key) { it.copy(state = TransferState.COMPLETED, bytes = offer.size) }
        } catch (t: Throwable) {
            fail(key, t.message ?: "send failed")
        } finally {
            synchronized(lock) { sendJobs.remove(key) }
        }
    }

    private suspend fun onChunk(peerId: String, chunk: ByteArray) {
        val state = synchronized(lock) {
            incoming.values.firstOrNull { it.peerId == peerId }
        } ?: return

        try {
            state.sink.write(chunk, chunk.size)
            state.hash.update(chunk)
            state.bytes += chunk.size
        } catch (t: Throwable) {
            failIncoming(state, t.message ?: "could not write file")
            return
        }

        val now = System.nanoTime()
        if (now - state.lastUpdateNanos > PROGRESS_INTERVAL_NANOS || state.bytes >= state.size) {
            state.lastUpdateNanos = now
            update(state.key) { it.copy(bytes = state.bytes) }
        }

        if (state.bytes >= state.size) finishIncoming(state)
    }

    private suspend fun finishIncoming(state: IncomingTransfer) {
        synchronized(lock) { incoming.remove(state.key) }

        val actual = state.hash.digest().toHex()
        if (!actual.equals(state.expectedSha, ignoreCase = true)) {
            runCatching { state.sink.abort() }
            fail(state.key, "hash mismatch - file discarded")
            return
        }

        val saved = runCatching { state.sink.commit() }.getOrElse { error ->
            fail(state.key, error.message ?: "could not save file")
            return
        }
        update(state.key) {
            it.copy(state = TransferState.COMPLETED, bytes = state.size, savedPath = saved)
        }
    }

    private suspend fun waitForChannel(peerId: String): PeerDataChannel? {
        repeat(CHANNEL_WAIT_SPINS) {
            channels[peerId]?.let { return it }
            delay(CHANNEL_WAIT_MS)
        }
        return channels[peerId]
    }

    private fun abortLocally(id: String, peerId: String) {
        val key = key(TransferDirection.INCOMING, id, peerId)
        val state = synchronized(lock) { incoming.remove(key) }
        if (state != null) launchAbort(state)

        val job = synchronized(lock) { sendJobs.remove(key(TransferDirection.OUTGOING, id, peerId)) }
        job?.cancel()
    }

    private fun abortPeerIncoming(peerId: String) {
        val states = synchronized(lock) {
            incoming.filterValues { it.peerId == peerId }.values.toList()
        }
        states.forEach { state ->
            synchronized(lock) { incoming.remove(state.key) }
            launchAbort(state)
            update(state.key) { it.copy(state = TransferState.CANCELLED, error = "peer left") }
        }
    }

    private fun launchAbort(state: IncomingTransfer) {
        scope.launch { runCatching { state.sink.abort() } }
    }

    private fun fail(key: String, reason: String) {
        update(key) { it.copy(state = TransferState.FAILED, error = reason) }
    }

    private suspend fun failIncoming(state: IncomingTransfer, reason: String) {
        synchronized(lock) { incoming.remove(state.key) }
        runCatching { state.sink.abort() }
        fail(state.key, reason)
    }

    private fun upsert(row: TransferProgress) {
        _transfers.update { list ->
            val existing = list.indexOfFirst { it.key == row.key }
            val updated = if (existing >= 0) {
                list.toMutableList().also { it[existing] = row }
            } else {
                list + row
            }
            updated.takeLast(MAX_ROWS)
        }
    }

    private fun update(key: String, transform: (TransferProgress) -> TransferProgress) {
        _transfers.update { list ->
            list.map { if (it.key == key) transform(it) else it }
        }
    }

    private fun key(direction: TransferDirection, id: String, peerId: String): String =
        "$direction:$id:$peerId"

    private fun TransferProgress.withKey(): TransferProgress =
        copy(key = key(direction, id, peerId))

    private class OutgoingOffer(
        val id: String,
        val path: String,
        val name: String,
        val size: Long,
        val sha256: String,
    )

    private class IncomingTransfer(
        val key: String,
        val peerId: String,
        val sink: FileSink,
        val hash: Sha256,
        val size: Long,
        val expectedSha: String,
    ) {
        var bytes: Long = 0
        var lastUpdateNanos: Long = 0
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val MAX_ROWS = 40
        const val PROGRESS_INTERVAL_NANOS = 100_000_000L
        const val CHANNEL_WAIT_MS = 100L
        const val CHANNEL_WAIT_SPINS = 50
    }
}
