package org.kotlinchat.media

import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink

/**
 * Wraps a frame callback so the native reference carried by [VideoFrame] is always released.
 *
 * webrtc-java hands every sink a `VideoFrame` whose buffer owns its own native reference and has
 * no finalizer; the sink is expected to call [VideoFrame.release]. A sink that skips it leaks a
 * full I420 copy per frame, which is megabytes per second even at 1080p. The frame buffer is only
 * valid during the callback, so releasing in a `finally` is safe.
 *
 * Sinks built here only read pixels, so they are registered with `VideoFrameAccess.SHARED`; that
 * avoids a second full copy of every frame on top of the decoded buffer.
 */
internal fun videoFrameSink(block: (VideoFrame) -> Unit): VideoTrackSink =
    VideoTrackSink { frame ->
        try {
            block(frame)
        } finally {
            runCatching { frame.release() }
        }
    }
