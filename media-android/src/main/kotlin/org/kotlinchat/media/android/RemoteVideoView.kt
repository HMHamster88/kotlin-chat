package org.kotlinchat.media.android

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Hardware-rendered view of one remote screen-share track.
 *
 * A `SurfaceViewRenderer` draws decoded frames directly through EGL, so there is no RGBA
 * conversion or per-frame allocation here. Create it on the main thread, bind it with
 * [setVideo], and call [release] when it leaves composition.
 */
class RemoteVideoView(context: Context) : FrameLayout(context) {

    private val eglBase: EglBase = EglBase.create()
    private var current: RemoteVideo? = null
    private var released = false

    /** Called on the main thread when the first frame has actually been drawn. */
    var onFirstFrame: (() -> Unit)? = null

    /** Called on the main thread with width, height and rotation after a resolution change. */
    var onResolutionChanged: ((Int, Int, Int) -> Unit)? = null

    private val renderer: SurfaceViewRenderer = object : SurfaceViewRenderer(context) {
        override fun onFirstFrameRendered() {
            super.onFirstFrameRendered()
            post { if (!released) onFirstFrame?.invoke() }
        }

        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
            super.onFrameResolutionChanged(width, height, rotation)
            post { if (!released) onResolutionChanged?.invoke(width, height, rotation) }
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
        // SurfaceViewRenderer sizes itself to the video's aspect ratio when it is allowed to
        // wrap content. Letting the parent stretch it to match-parent is what distorts the
        // picture, so center it inside this black frame instead.
        addView(
            renderer,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    /** Swaps the displayed track. Pass null when the share ends. Main thread only. */
    fun setVideo(video: RemoteVideo?) {
        if (released) return
        if (video?.track === current?.track) return
        current?.track?.let { track -> runCatching { track.removeSink(renderer) } }
        current = video
        video?.track?.let { track -> runCatching { track.addSink(renderer) } }
    }

    /** Releases the renderer and its EGL context. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true
        current?.track?.let { track -> runCatching { track.removeSink(renderer) } }
        current = null
        onFirstFrame = null
        onResolutionChanged = null
        runCatching { renderer.release() }
        runCatching { eglBase.release() }
    }
}
