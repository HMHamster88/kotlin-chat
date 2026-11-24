package org.kotlinchat.media

import javax.swing.JComponent
import org.slf4j.LoggerFactory

/**
 * Common surface for the native desktop video renderers. The Compose layer only needs these
 * members, so the OpenGL and software implementations can be swapped without UI changes.
 */
abstract class DesktopVideoView : JComponent() {
    abstract var onFirstFrame: (() -> Unit)?
    abstract var onResolution: ((Int, Int) -> Unit)?
    abstract fun setVideo(video: RemoteVideo?)
    abstract fun release()
}

/**
 * Prefers the GPU YUV renderer and falls back to the software ABGR canvas when OpenGL is not
 * available. Set `-Dkotlinchat.videoRenderer=software` to force the fallback.
 */
fun createDesktopVideoView(): DesktopVideoView {
    if (System.getProperty("kotlinchat.videoRenderer").equals("software", ignoreCase = true)) {
        return RemoteVideoView()
    }
    return runCatching { OpenGlVideoView() }.getOrElse { error ->
        LoggerFactory.getLogger("DesktopVideoView")
            .warn("OpenGL video renderer unavailable; using the software canvas", error)
        RemoteVideoView()
    }
}
