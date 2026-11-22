package org.kotlinchat.media

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.client.rtc.RtcOptions
import kotlin.test.Test
import kotlin.test.assertEquals
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
            println("loopback audio: alice->bob=${toBob.bytesReceived}B bob->alice=${toAlice.bytesReceived}B")
        } finally {
            pumps.forEach { it.cancel() }
            alice.close()
            bob.close()
        }
    }

    @Test
    fun `screen share frames reach the peer and stop when sharing stops`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device on this machine")

        val sources = engine.screenSources()
        assumeTrue(sources.isNotEmpty(), "no screen capture sources available")
        val screen = sources.firstOrNull { !it.isWindow } ?: sources.first()

        val alice = engine.start(RtcOptions())
        val bob = engine.start(RtcOptions())
        val pumps = mutableListOf<kotlinx.coroutines.Job>()

        try {
            pumps += launch(start = CoroutineStart.UNDISPATCHED) {
                alice.outgoing.collect { out -> bob.handleSignal("s_alice", out.signal) }
            }
            pumps += launch(start = CoroutineStart.UNDISPATCHED) {
                bob.outgoing.collect { out -> alice.handleSignal("s_bob", out.signal) }
            }

            alice.addPeer("s_bob", polite = false)
            bob.addPeer("s_alice", polite = true)

            withTimeout(30_000) {
                bob.links.first { it["s_alice"]?.state == PeerLinkState.CONNECTED }
            }

            alice.startScreenShare(screen, maxWidth = 640, maxHeight = 360, fps = 5)

            val firstFrames = withTimeout(30_000) { bob.remoteFrames.first { it.isNotEmpty() } }
            val first = firstFrames.values.first()
            assertTrue(first.width in 2..640, "preview width ${first.width}")
            assertTrue(first.height in 2..360, "preview height ${first.height}")
            assertTrue(first.rgba.size == first.width * first.height * 4)

            val second = withTimeout(20_000) {
                bob.remoteFrames.first { frames ->
                    (frames.values.firstOrNull()?.sequence ?: 0L) > first.sequence
                }
            }.values.first()
            assertTrue(second.sequence > first.sequence, "frames should keep arriving")

            alice.stopScreenShare()
            delay(1_500)
            val stoppedAt = bob.remoteFrames.value.values.firstOrNull()?.sequence ?: 0L
            delay(1_500)
            val after = bob.remoteFrames.value.values.firstOrNull()?.sequence ?: 0L
            assertEquals(stoppedAt, after, "no frames should arrive after stopScreenShare")
        } finally {
            pumps.forEach { it.cancel() }
            alice.close()
            bob.close()
        }
    }
}
