package org.kotlinchat.integration

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.PinStore
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.media.WebRtcDesktopEngine
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in smoke test against a server that is already running, used to verify real hardware
 * end to end (for example a phone talking to the desktop).
 *
 * Skipped unless CHAT_SMOKE_HOST is set:
 *   CHAT_SMOKE_HOST=127.0.0.1 CHAT_SMOKE_PORT=8443 CHAT_SMOKE_PASSWORD=your-server-password
 */
class LiveServerSmokeTest {

    @Test
    fun `joins a live server and receives audio from another client`() = runBlocking {
        val host = System.getenv("CHAT_SMOKE_HOST")
        assumeTrue(host != null, "CHAT_SMOKE_HOST not set")

        val port = System.getenv("CHAT_SMOKE_PORT")?.toIntOrNull() ?: 8443
        val password = System.getenv("CHAT_SMOKE_PASSWORD") ?: ""
        val tls = System.getenv("CHAT_SMOKE_PLAIN") != "1"

        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        val client = ChatClient(this, SmokePins(), engine)
        try {
            client.connect(ServerEndpoint(host!!, port, tls), "desktop-smoke", password)
            withTimeout(30_000) { client.snapshot.first { it.connection is ConnectionState.Ready } }
            client.setMicrophoneMuted(false)

            withTimeout(60_000) { client.snapshot.first { it.voice.connectedPeers >= 1 } }
            val links = client.snapshot.value.voice.links
            println("LIVE PEERS: $links")

            withTimeout(60_000) {
                client.snapshot.first { snapshot ->
                    snapshot.voice.links.values.any { it.bytesReceived > 0 }
                }
            }
            val peer = client.snapshot.value.voice.links.values.first()
            assertTrue(peer.bytesReceived > 0, "expected audio from the remote peer")
            println("LIVE AUDIO: ${peer.bytesReceived} bytes from the remote peer")
        } finally {
            client.close()
        }
    }

    @Test
    fun `receives a screen share from another client`() = runBlocking {
        val host = System.getenv("CHAT_SMOKE_HOST")
        assumeTrue(host != null, "CHAT_SMOKE_HOST not set")

        val port = System.getenv("CHAT_SMOKE_PORT")?.toIntOrNull() ?: 8443
        val password = System.getenv("CHAT_SMOKE_PASSWORD") ?: ""
        val tls = System.getenv("CHAT_SMOKE_PLAIN") != "1"

        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        val client = ChatClient(this, SmokePins(), engine)
        try {
            client.connect(ServerEndpoint(host!!, port, tls), "desktop-screen", password)
            withTimeout(30_000) { client.snapshot.first { it.connection is ConnectionState.Ready } }
            withTimeout(60_000) { client.snapshot.first { it.members.size >= 2 } }
            withTimeout(60_000) { client.snapshot.first { it.voice.connectedPeers >= 1 } }
            println("LIVE SCREEN: peer connected, waiting for frames")

            withTimeout(180_000) { client.snapshot.first { it.screen.remote != null } }
            val frame = client.snapshot.value.screen.remote!!
            assertTrue(frame.width > 0 && frame.height > 0)
            println("LIVE SCREEN: ${frame.width}x${frame.height} seq=${frame.sequence}")

            withTimeout(30_000) {
                client.snapshot.first { snapshot ->
                    (snapshot.screen.remote?.sequence ?: 0) > frame.sequence
                }
            }
            println("LIVE SCREEN: more frames arrived")
        } finally {
            client.close()
        }
    }
}

private class SmokePins : PinStore {
    private val values = mutableMapOf<String, String>()

    override fun get(hostKey: String): String? = values[hostKey]

    override fun put(hostKey: String, fingerprint: String) {
        values[hostKey] = fingerprint
    }
}
