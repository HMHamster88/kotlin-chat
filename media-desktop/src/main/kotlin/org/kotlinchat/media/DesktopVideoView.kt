package org.kotlinchat.media

import java.awt.Component
import java.awt.Cursor
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import kotlin.math.min
import org.kotlinchat.client.control.RemoteMouseButton
import org.slf4j.LoggerFactory

/** Viewer-side callbacks for an active remote-control session. */
class VideoInput(
    val onPointer: (x: Float, y: Float) -> Unit,
    val onButton: (button: RemoteMouseButton, down: Boolean) -> Unit,
    val onWheel: (delta: Int, horizontal: Boolean) -> Unit,
    val onKey: (keyCode: Int, down: Boolean) -> Unit,
)

/**
 * Common surface for the native desktop video renderers. The Compose layer only needs these
 * members, so the OpenGL and software implementations can be swapped without UI changes.
 *
 * Input capture lives here so both renderers map the same letterboxed video rectangle. The
 * inline pane never enables it; only the separate share window does. Subclasses attach the
 * listeners to their real input surface: themselves for the software canvas, the GL panel for
 * the OpenGL renderer.
 */
abstract class DesktopVideoView : JComponent() {
    abstract var onFirstFrame: (() -> Unit)?
    abstract var onResolution: ((Int, Int) -> Unit)?
    abstract fun setVideo(video: RemoteVideo?)
    abstract fun release()

    @Volatile
    protected var videoWidth = 0

    @Volatile
    protected var videoHeight = 0

    private var input: VideoInput? = null
    private var inputTarget: Component? = null
    private val pressedButtons = mutableSetOf<RemoteMouseButton>()
    private val pressedKeys = mutableSetOf<Int>()

    private val mouseHandler = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            val handler = input ?: return
            val target = inputTarget
            if (target != null && !target.hasFocus()) {
                target.requestFocus()
                return
            }
            if (normalized(event.x, event.y) == null) return
            sendPointer(handler, event.x, event.y)
            val button = controlButton(event.button) ?: return
            pressedButtons += button
            handler.onButton(button, true)
        }

        override fun mouseReleased(event: MouseEvent) {
            val handler = input ?: return
            val button = controlButton(event.button) ?: return
            if (!pressedButtons.remove(button)) return
            handler.onButton(button, false)
        }

        override fun mouseMoved(event: MouseEvent) {
            val handler = input ?: return
            sendPointer(handler, event.x, event.y)
        }

        override fun mouseDragged(event: MouseEvent) {
            val handler = input ?: return
            sendPointer(handler, event.x, event.y)
        }
    }

    private val wheelHandler = object : MouseAdapter() {
        override fun mouseWheelMoved(event: MouseWheelEvent) {
            val handler = input ?: return
            if (normalized(event.x, event.y) == null) return
            handler.onWheel(event.wheelRotation, false)
        }
    }

    private val keyHandler = object : KeyAdapter() {
        override fun keyPressed(event: KeyEvent) {
            val handler = input ?: return
            if (pressedKeys.add(event.keyCode)) handler.onKey(event.keyCode, true)
        }

        override fun keyReleased(event: KeyEvent) {
            if (!pressedKeys.remove(event.keyCode)) return
            input?.onKey(event.keyCode, false)
        }
    }

    private val focusHandler = object : FocusAdapter() {
        override fun focusLost(event: FocusEvent) {
            releaseHeld()
        }
    }

    /** Call from a subclass init with the component that actually receives AWT input. */
    protected fun attachInput(component: Component) {
        if (inputTarget === component) return
        inputTarget?.let { old ->
            old.removeMouseListener(mouseHandler)
            old.removeMouseMotionListener(mouseHandler)
            old.removeMouseWheelListener(wheelHandler)
            old.removeKeyListener(keyHandler)
            old.removeFocusListener(focusHandler)
        }
        inputTarget = component
        component.isFocusable = true
        component.addMouseListener(mouseHandler)
        component.addMouseMotionListener(mouseHandler)
        component.addMouseWheelListener(wheelHandler)
        component.addKeyListener(keyHandler)
        component.addFocusListener(focusHandler)
    }

    /** Enables/disables viewer input. Pass null for the inline, view-only pane. */
    fun setInput(input: VideoInput?) {
        if (this.input === input) return
        if (this.input != null) releaseHeld()
        this.input = input
        val target = inputTarget
        if (input == null) {
            cursor = Cursor.getDefaultCursor()
            target?.cursor = Cursor.getDefaultCursor()
        } else {
            val crosshair = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            cursor = crosshair
            target?.let {
                it.cursor = crosshair
                if (it is JComponent) it.requestFocusInWindow() else it.requestFocus()
            }
        }
    }

    protected fun releaseInput() {
        if (input != null) releaseHeld()
    }

    private fun releaseHeld() {
        val handler = input ?: return
        val buttons = pressedButtons.toList()
        pressedButtons.clear()
        buttons.forEach { handler.onButton(it, false) }
        val keys = pressedKeys.toList()
        pressedKeys.clear()
        keys.forEach { handler.onKey(it, false) }
    }

    private fun sendPointer(handler: VideoInput, x: Int, y: Int) {
        val (nx, ny) = normalized(x, y) ?: return
        handler.onPointer(nx, ny)
    }

    private fun normalized(x: Int, y: Int): Pair<Float, Float>? {
        val fw = videoWidth
        val fh = videoHeight
        if (fw <= 0 || fh <= 0) return null
        val w = inputTarget?.width ?: width
        val h = inputTarget?.height ?: height
        if (w <= 0 || h <= 0) return null
        val scale = min(w.toDouble() / fw, h.toDouble() / fh)
        val drawWidth = (fw * scale).toDouble()
        val drawHeight = (fh * scale).toDouble()
        val left = (w - drawWidth) / 2.0
        val top = (h - drawHeight) / 2.0
        if (x < left || x > left + drawWidth || y < top || y > top + drawHeight) return null
        return ((x - left) / drawWidth).toFloat() to ((y - top) / drawHeight).toFloat()
    }

    private fun controlButton(button: Int): RemoteMouseButton? = when (button) {
        MouseEvent.BUTTON1 -> RemoteMouseButton.LEFT
        MouseEvent.BUTTON2 -> RemoteMouseButton.MIDDLE
        MouseEvent.BUTTON3 -> RemoteMouseButton.RIGHT
        else -> null
    }
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
