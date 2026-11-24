package org.kotlinchat.media.android

import com.shepeliev.webrtckmp.VideoTrack

/**
 * Opaque handle to one peer's incoming screen-share track.
 *
 * The UI passes this to [RemoteVideoView]; the WebRTC track stays inside this module, so
 * `app-android` never needs to import the WebRTC types.
 */
class RemoteVideo internal constructor(
    val peerId: String,
    internal val track: VideoTrack,
)
