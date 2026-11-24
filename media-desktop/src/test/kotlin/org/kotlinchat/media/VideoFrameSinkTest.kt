package org.kotlinchat.media

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * webrtc-java hands every sink a [VideoFrame] that owns a native reference and is never reclaimed
 * by the JVM, so a sink that forgets [VideoFrame.release] leaks a full I420 copy per frame. This
 * pins the wrapper that does it for the desktop renderers.
 */
class VideoFrameSinkTest {

    @Test
    fun `releases the frame after the callback`() {
        val buffer = CountingBuffer()
        val frame = VideoFrame(buffer, 0L)
        var seen: VideoFrame? = null

        videoFrameSink { seen = it }.onVideoFrame(frame)

        assertSame(frame, seen)
        assertEquals(1, buffer.releases)
    }

    @Test
    fun `releases the frame when the callback throws`() {
        val buffer = CountingBuffer()
        val frame = VideoFrame(buffer, 0L)

        val sink = videoFrameSink { error("renderer failed") }

        assertFailsWith<IllegalStateException> { sink.onVideoFrame(frame) }
        assertEquals(1, buffer.releases)
    }
}

private class CountingBuffer : VideoFrameBuffer {

    var releases = 0

    override fun getWidth(): Int = 4

    override fun getHeight(): Int = 2

    override fun toI420(): I420Buffer = throw UnsupportedOperationException("not used")

    override fun cropAndScale(
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int,
        scaleWidth: Int,
        scaleHeight: Int,
    ): VideoFrameBuffer = throw UnsupportedOperationException("not used")

    override fun retain() = Unit

    override fun release() {
        releases++
    }
}
