package org.kotlinchat.media

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.client.rtc.RtcOptions
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two engines on one machine, signaling wired directly instead of through the server.
 *
 * Asserts what matters and can be measured headlessly: SDP/ICE completes, DTLS is up, and RTP
 * bytes are arriving in both directions.
 */
class LoopbackAudioTest {

    @Test
    fun `two engines connect and exchange audio`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        val alice = engine.start(RtcOptions())
        val bob = engine.start(RtcOptions())
        val pumps = mutableListOf<kotlinx.coroutines.Job>()

        try {
            alice.setMuted(false)
            bob.setMuted(false)

            pumps += launch(start = CoroutineStart.UNDISPATCHED) {
                alice.outgoing.collect { out -> bob.handleSignal("s_alice", out.signal) }
            }
            pumps += launch(start = CoroutineStart.UNDISPATCHED) {
                bob.outgoing.collect { out -> alice.handleSignal("s_bob", out.signal) }
            }

            // "s_alice" < "s_bob": alice is impolite and offers, bob waits.
            alice.addPeer("s_bob", polite = false)
            bob.addPeer("s_alice", polite = true)

            withTimeout(30_000) {
                alice.links.first { it["s_bob"]?.state == PeerLinkState.CONNECTED }
            }
            withTimeout(30_000) {
                bob.links.first { it["s_alice"]?.state == PeerLinkState.CONNECTED }
            }

            val toBob = withTimeout(30_000) {
                bob.links.first { (it["s_alice"]?.bytesReceived ?: 0L) > 0L }
            }["s_alice"]!!
            val toAlice = withTimeout(30_000) {
                alice.links.first { (it["s_bob"]?.bytesReceived ?: 0L) > 0L }
            }["s_bob"]!!

            assertTrue(toBob.bytesReceived > 0, "alice -> bob audio bytes")
            assertTrue(toAlice.bytesReceived > 0, "bob -> alice audio bytes")
            println("loopback audio: alice->bob=${toBob.bytesReceived}B bob->alice=${toAlice.bytesReceived}B")        } finally {
            pumps.forEach { it.cancel() }
            alice.close()
            bob.close()
        }
    }
}
