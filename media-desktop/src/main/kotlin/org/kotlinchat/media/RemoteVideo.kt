package org.kotlinchat.media

import dev.onvoid.webrtc.media.video.VideoTrack

/**
 * Opaque handle to one peer's incoming screen-share track.
 *
 * The UI passes this to [RemoteVideoView]; the WebRTC track stays inside this module.
 */
class RemoteVideo internal constructor(
    val peerId: String,
    internal val track: VideoTrack,
)
