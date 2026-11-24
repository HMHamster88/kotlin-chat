package org.kotlinchat.media

import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import javax.swing.SwingUtilities
import kotlin.math.min

/**
 * Paints a remote screen-share track at its native resolution and frame rate.
 *
 * This bypasses the RGBA preview flow: frames are written straight into the `BufferedImage`
 * that [paintComponent] draws, so no Compose snapshot update happens per frame.
 *
 * The conversion uses webrtc-java's `FourCC.RGBA` because that constant writes A,B,G,R bytes,
 * which is exactly the memory layout of [BufferedImage.TYPE_4BYTE_ABGR]. (Its `ABGR` constant
 * writes R,G,B,A.)
 */
class RemoteVideoView : DesktopVideoView() {

    private val lock = Any()
    private var video: RemoteVideo? = null
    private var sink: VideoTrackSink? = null
    private var image: BufferedImage? = null

    @Volatile
    private var firstFrame = false

    /** Called on the EDT when the first frame of a track has arrived. */
    override var onFirstFrame: (() -> Unit)? = null

    /** Called on the EDT when the incoming frame size changes. */
    override var onResolution: ((Int, Int) -> Unit)? = null

    init {
        isOpaque = true
        background = Color.BLACK
    }

    /** Swaps the displayed track. Pass null when the share ends. Call on the EDT. */
    override fun setVideo(video: RemoteVideo?) {
        if (video?.track === this.video?.track) {
            repaint()
            return
        }
        sink?.let { old -> this.video?.track?.let { track -> runCatching { track.removeSink(old) } } }
        sink = null
        this.video = video
        synchronized(lock) {
            image = null
        }
        firstFrame = false

        if (video != null) {
            val next = VideoTrackSink { frame -> onFrame(frame) }
            sink = next
            runCatching { video.track.addSink(next) }
        }
        repaint()
    }

    /** Detaches the sink. Safe to call more than once. */
    override fun release() {
        sink?.let { old -> video?.track?.let { track -> runCatching { track.removeSink(old) } } }
        sink = null
        video = null
        synchronized(lock) { image = null }
        onFirstFrame = null
        onResolution = null
    }

    private fun onFrame(frame: VideoFrame) {
        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) return

        var resized = false
        synchronized(lock) {
            val target = image?.takeIf { it.width == width && it.height == height }
                ?: BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR).also {
                    image = it
                    resized = true
                }
            val bytes = (target.raster.dataBuffer as DataBufferByte).data
            runCatching { VideoBufferConverter.convertFromI420(buffer, bytes, FourCC.RGBA) }
                .onFailure { return }
        }

        if (resized) {
            SwingUtilities.invokeLater { onResolution?.invoke(width, height) }
        }
        if (!firstFrame) {
            firstFrame = true
            SwingUtilities.invokeLater { onFirstFrame?.invoke() }
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val frame = synchronized(lock) { image } ?: return
        val g2 = g as Graphics2D
        val scale = min(width.toDouble() / frame.width, height.toDouble() / frame.height)
        if (scale <= 0.0) return
        val drawWidth = (frame.width * scale).toInt().coerceAtLeast(1)
        val drawHeight = (frame.height * scale).toInt().coerceAtLeast(1)
        val x = (width - drawWidth) / 2
        val y = (height - drawHeight) / 2
        g2.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        // Hold the lock while drawing so the next frame cannot overwrite this one mid-paint.
        synchronized(lock) { g2.drawImage(frame, x, y, drawWidth, drawHeight, null) }
    }
}
