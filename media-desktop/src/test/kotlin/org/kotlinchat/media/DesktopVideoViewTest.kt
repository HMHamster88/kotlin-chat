package org.kotlinchat.media

import java.awt.Color
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.kotlinchat.client.rtc.PeerLinkState
import org.kotlinchat.client.rtc.RtcOptions
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Renders a synthetic four-quadrant window through the native desktop view and checks the
 * painted pixels. This is the regression test for the webrtc-java ABGR/RGBA byte-order quirk:
 * the old conversion turned green into magenta and blue into yellow.
 */
class DesktopVideoViewTest {

    @Test
    fun `native view paints the source colours and letterboxes the aspect`() = runBlocking {
        val engine = WebRtcDesktopEngine()
        assumeTrue(engine.available(), "no audio capture device")

        val window = JFrame("KotlinChatViewProbe")
        SwingUtilities.invokeAndWait {
            window.setSize(480, 300)
            window.setLocation(40, 40)
            window.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            window.contentPane = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    g.color = Color.RED
                    g.fillRect(0, 0, width / 2, height / 2)
                    g.color = Color.GREEN
                    g.fillRect(width / 2, 0, width / 2, height / 2)
                    g.color = Color.BLUE
                    g.fillRect(0, height / 2, width / 2, height / 2)
                    g.color = Color.WHITE
                    g.fillRect(width / 2, height / 2, width / 2, height / 2)
                }
            }
            window.isVisible = true
        }

        val alice = engine.start(RtcOptions())
        val bob = engine.start(RtcOptions())
        val pumps = mutableListOf<kotlinx.coroutines.Job>()
        var view: RemoteVideoView? = null
        try {
            delay(1_500)
            val source = engine.screenSources().firstOrNull {
                it.isWindow && it.title.contains("KotlinChatViewProbe", ignoreCase = true)
            }
            assertNotNull(source, "probe window missing")

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

            alice.startScreenShare(source, maxWidth = 1280, maxHeight = 720, fps = 10)
            val track = withTimeout(30_000) {
                engine.remoteVideos.first { it.isNotEmpty() }
            }.values.first()

            val firstFrame = CompletableDeferred<Unit>()
            val created = RemoteVideoView()
            SwingUtilities.invokeAndWait {
                created.setSize(640, 360)
                created.onFirstFrame = { firstFrame.complete(Unit) }
                created.setVideo(track)
            }
            view = created
            withTimeout(15_000) { firstFrame.await() }
            delay(500)

            val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            SwingUtilities.invokeAndWait { created.paint(graphics) }
            graphics.dispose()

            var red = 0
            var green = 0
            var blue = 0
            var white = 0
            var black = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    val r = (rgb shr 16) and 0xff
                    val g = (rgb shr 8) and 0xff
                    val b = rgb and 0xff
                    when {
                        r > 200 && g < 60 && b < 60 -> red++
                        r < 60 && g > 200 && b < 60 -> green++
                        r < 60 && g < 60 && b > 200 -> blue++
                        r > 200 && g > 200 && b > 200 -> white++
                        r < 20 && g < 20 && b < 20 -> black++
                    }
                }
            }
            println("painted pixels: red=$red green=$green blue=$blue white=$white black=$black")
            assertTrue(red > 1_000, "red quadrant missing")
            assertTrue(green > 1_000, "green quadrant missing (magenta regression?)")
            assertTrue(blue > 1_000, "blue quadrant missing (yellow regression?)")
            assertTrue(white > 1_000, "white quadrant missing")
            assertTrue(black > 100, "the source should be letterboxed at this size")
        } finally {
            view?.let { created -> SwingUtilities.invokeAndWait { created.release() } }
            pumps.forEach { it.cancel() }
            alice.close()
            bob.close()
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
