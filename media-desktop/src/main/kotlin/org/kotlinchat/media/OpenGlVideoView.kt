package org.kotlinchat.media

import com.jogamp.opengl.GL3
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLJPanel
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameAccess
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.awt.BorderLayout
import java.awt.Color
import java.nio.ByteBuffer
import javax.swing.SwingUtilities
import kotlin.math.min
import org.slf4j.LoggerFactory

/**
 * OpenGL YUV renderer for desktop screen shares.
 *
 * The decoded frame is copied once into a packed I420 buffer; the three planes are uploaded as
 * single-channel textures and a fragment shader does BT.601 limited-range YUV→RGB plus the
 * aspect-fit scaling. This removes the CPU colour conversion and the Java2D scale/upload work
 * from the render path while keeping the same WebRTC stack.
 */
class OpenGlVideoView : DesktopVideoView() {

    private val log = LoggerFactory.getLogger(OpenGlVideoView::class.java)
    private val lock = Any()

    private var video: RemoteVideo? = null
    private var sink: VideoTrackSink? = null
    private var pending: PlaneFrame? = null

    /**
     * Recycled I420 packets. A 4K frame is ~12 MB, so allocating one per displayed frame means
     * hundreds of megabytes per second of direct-buffer churn; a small pool makes steady-state
     * rendering allocation-free. Two is enough: one is being uploaded while the other rotates
     * with [pending].
     */
    private val freeFrames = ArrayDeque<PlaneFrame>()
    private val displayScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var released = false

    @Volatile
    private var firstFrame = false

    @Volatile
    override var onFirstFrame: (() -> Unit)? = null

    @Volatile
    override var onResolution: ((Int, Int) -> Unit)? = null

    private var program = 0
    private var vao = 0
    private val textures = IntArray(3)
    private var yUniform = -1
    private var uUniform = -1
    private var vUniform = -1
    private var scaleUniform = -1
    private var textureWidth = 0
    private var textureHeight = 0
    private var frameWidth = 0
    private var frameHeight = 0

    private val panel: GLJPanel

    init {
        val profile = GLProfile.get(GLProfile.GL3)
        val capabilities = GLCapabilities(profile).apply {
            setDoubleBuffered(true)
            setSampleBuffers(false)
        }
        panel = GLJPanel(capabilities)
        layout = BorderLayout()
        background = Color.BLACK
        panel.isOpaque = true
        panel.addGLEventListener(GlRenderer())
        add(panel, BorderLayout.CENTER)
        attachInput(panel)
    }

    /** Swaps the displayed track. Pass null when the share ends. Call on the EDT. */
    override fun setVideo(video: RemoteVideo?) {
        if (released) return
        if (video?.track === this.video?.track) return
        sink?.let { old -> this.video?.track?.let { track -> runCatching { track.removeSink(old) } } }
        sink = null
        this.video = video
        synchronized(lock) {
            pending = null
            freeFrames.clear()
        }
        firstFrame = false
        frameWidth = 0
        frameHeight = 0
        if (video != null) {
            val next = videoFrameSink { frame -> onFrame(frame) }
            sink = next
            runCatching { video.track.addSink(next, VideoFrameAccess.SHARED) }
        }
        SwingUtilities.invokeLater { runCatching { panel.display() } }
    }

    /** Detaches the sink and releases the GL panel. Safe to call more than once. */
    override fun release() {
        if (released) return
        released = true
        releaseInput()
        sink?.let { old -> video?.track?.let { track -> runCatching { track.removeSink(old) } } }
        sink = null
        video = null
        synchronized(lock) {
            pending = null
            freeFrames.clear()
        }
        onFirstFrame = null
        onResolution = null
        runCatching { panel.destroy() }
    }

    private fun onFrame(frame: VideoFrame) {
        if (released) return
        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height

        val uvWidth = (width + 1) / 2
        val uvHeight = (height + 1) / 2
        val size = width * height + 2 * uvWidth * uvHeight
        val packet = synchronized(lock) { acquireLocked(width, height, size) }

        packet.buffer.clear()
        runCatching {
            VideoBufferConverter.convertFromI420(buffer, packet.buffer, FourCC.I420)
        }.onFailure {
            log.warn("could not copy an I420 frame: {}", it.message)
            synchronized(lock) { recycleLocked(packet) }
            return
        }

        synchronized(lock) {
            val old = pending
            pending = packet
            if (old != null) recycleLocked(old)
        }
        if (displayScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                displayScheduled.set(false)
                if (!released) runCatching { panel.display() }
            }
        }
    }

    /** Takes a same-size packet from the pool, or allocates one when the pool is empty. */
    private fun acquireLocked(width: Int, height: Int, size: Int): PlaneFrame {
        while (freeFrames.isNotEmpty()) {
            val candidate = freeFrames.removeLast()
            if (candidate.width == width && candidate.height == height) return candidate
            // A resized share invalidates the old packets; let the GC reclaim them.
        }
        return PlaneFrame(ByteBuffer.allocateDirect(size), width, height)
    }

    private fun recycleLocked(packet: PlaneFrame) {
        if (freeFrames.size < MAX_FREE_FRAMES) freeFrames.addLast(packet)
    }

    private fun upload(gl: GL3, frame: PlaneFrame) {
        val ySize = frame.width * frame.height
        val uvSize = frame.uvWidth * frame.uvHeight
        val data = frame.buffer

        if (frame.width != textureWidth || frame.height != textureHeight) {
            textureWidth = frame.width
            textureHeight = frame.height
            gl.glActiveTexture(GL3.GL_TEXTURE4)
            gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[0])
            gl.glTexImage2D(
                GL3.GL_TEXTURE_2D, 0, GL3.GL_R8, frame.width, frame.height, 0,
                GL3.GL_RED, GL3.GL_UNSIGNED_BYTE, null as ByteBuffer?,
            )
            gl.glActiveTexture(GL3.GL_TEXTURE5)
            gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[1])
            gl.glTexImage2D(
                GL3.GL_TEXTURE_2D, 0, GL3.GL_R8, frame.uvWidth, frame.uvHeight, 0,
                GL3.GL_RED, GL3.GL_UNSIGNED_BYTE, null as ByteBuffer?,
            )
            gl.glActiveTexture(GL3.GL_TEXTURE6)
            gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[2])
            gl.glTexImage2D(
                GL3.GL_TEXTURE_2D, 0, GL3.GL_R8, frame.uvWidth, frame.uvHeight, 0,
                GL3.GL_RED, GL3.GL_UNSIGNED_BYTE, null as ByteBuffer?,
            )
        }

        val y = data.duplicate().position(0).limit(ySize).slice()
        val u = data.duplicate().position(ySize).limit(ySize + uvSize).slice()
        val v = data.duplicate().position(ySize + uvSize).limit(ySize + 2 * uvSize).slice()
        gl.glActiveTexture(GL3.GL_TEXTURE4)
        gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[0])
        gl.glTexSubImage2D(
            GL3.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
            GL3.GL_RED, GL3.GL_UNSIGNED_BYTE, y,
        )
        gl.glActiveTexture(GL3.GL_TEXTURE5)
        gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[1])
        gl.glTexSubImage2D(
            GL3.GL_TEXTURE_2D, 0, 0, 0, frame.uvWidth, frame.uvHeight,
            GL3.GL_RED, GL3.GL_UNSIGNED_BYTE, u,
        )
        gl.glActiveTexture(GL3.GL_TEXTURE6)
        gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[2])
        gl.glTexSubImage2D(
            GL3.GL_TEXTURE_2D, 0, 0, 0, frame.uvWidth, frame.uvHeight,
            GL3.GL_RED, GL3.GL_UNSIGNED_BYTE, v,
        )
        gl.glActiveTexture(GL3.GL_TEXTURE0)
    }

    private inner class GlRenderer : GLEventListener {

        override fun init(drawable: GLAutoDrawable) {
            val gl = drawable.gl.getGL3()
            log.info(
                "OpenGL {} / {} / GLSL {}",
                gl.glGetString(GL3.GL_VENDOR),
                gl.glGetString(GL3.GL_RENDERER),
                gl.glGetString(GL3.GL_SHADING_LANGUAGE_VERSION),
            )
            gl.glDisable(GL3.GL_DEPTH_TEST)
            gl.glDisable(GL3.GL_BLEND)
            gl.glPixelStorei(GL3.GL_UNPACK_ALIGNMENT, 1)
            gl.glClearColor(0f, 0f, 0f, 1f)

            val vertexShader = compile(gl, GL3.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = compile(gl, GL3.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = gl.glCreateProgram()
            gl.glAttachShader(program, vertexShader)
            gl.glAttachShader(program, fragmentShader)
            gl.glLinkProgram(program)
            val linked = IntArray(1)
            gl.glGetProgramiv(program, GL3.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val info = ByteArray(4096)
                gl.glGetProgramInfoLog(program, info.size, null, 0, info, 0)
                error("shader link failed: ${String(info, Charsets.UTF_8).trimEnd('\u0000')}")
            }
            gl.glDeleteShader(vertexShader)
            gl.glDeleteShader(fragmentShader)
            yUniform = gl.glGetUniformLocation(program, "u_y")
            uUniform = gl.glGetUniformLocation(program, "u_u")
            vUniform = gl.glGetUniformLocation(program, "u_v")
            scaleUniform = gl.glGetUniformLocation(program, "u_scale")
            gl.glUseProgram(program)
            gl.glUniform1i(yUniform, 4)
            gl.glUniform1i(uUniform, 5)
            gl.glUniform1i(vUniform, 6)

            val ids = IntArray(1)
            gl.glGenVertexArrays(1, ids, 0)
            vao = ids[0]
            gl.glBindVertexArray(vao)

            gl.glGenTextures(3, textures, 0)
            for (index in 0..2) {
                gl.glActiveTexture(GL3.GL_TEXTURE4 + index)
                gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[index])
                gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR)
                gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR)
                gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE)
                gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE)
            }
            gl.glActiveTexture(GL3.GL_TEXTURE0)
            textureWidth = 0
            textureHeight = 0
        }

        override fun display(drawable: GLAutoDrawable) {
            val gl = drawable.gl.getGL3()
            val surfaceWidth = drawable.surfaceWidth
            val surfaceHeight = drawable.surfaceHeight
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            gl.glViewport(0, 0, surfaceWidth, surfaceHeight)
            gl.glClear(GL3.GL_COLOR_BUFFER_BIT)

            val packet = synchronized(lock) { pending.also { pending = null } }
            if (packet != null) {
                if (packet.width != frameWidth || packet.height != frameHeight) {
                    frameWidth = packet.width
                    frameHeight = packet.height
                    SwingUtilities.invokeLater { onResolution?.invoke(packet.width, packet.height) }
                }
                runCatching { upload(gl, packet) }
                    .onFailure { log.warn("could not upload a frame: {}", it.message) }
                synchronized(lock) { recycleLocked(packet) }
                if (!firstFrame) {
                    firstFrame = true
                    SwingUtilities.invokeLater { onFirstFrame?.invoke() }
                }
            }
            if (frameWidth <= 0 || frameHeight <= 0) return

            val scale = min(
                surfaceWidth.toDouble() / frameWidth,
                surfaceHeight.toDouble() / frameHeight,
            )
            val drawWidth = (frameWidth * scale).toInt().coerceAtLeast(1)
            val drawHeight = (frameHeight * scale).toInt().coerceAtLeast(1)
            gl.glUseProgram(program)
            gl.glUniform2f(
                scaleUniform,
                drawWidth.toFloat() / surfaceWidth,
                drawHeight.toFloat() / surfaceHeight,
            )
            gl.glActiveTexture(GL3.GL_TEXTURE4)
            gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[0])
            gl.glActiveTexture(GL3.GL_TEXTURE5)
            gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[1])
            gl.glActiveTexture(GL3.GL_TEXTURE6)
            gl.glBindTexture(GL3.GL_TEXTURE_2D, textures[2])
            gl.glBindVertexArray(vao)
            gl.glDrawArrays(GL3.GL_TRIANGLE_STRIP, 0, 4)
            gl.glActiveTexture(GL3.GL_TEXTURE0)
            // Deliberately leave our program and VAO bound. JOGL's GLJPanel blit reuses the
            // current program/VAO; clearing them makes the panel render black in this setup.
            // Our textures live on units 4-6 so unit 0 (used by the blit) is untouched.
        }

        override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {
            // display() recomputes the viewport from the surface size every frame.
        }

        override fun dispose(drawable: GLAutoDrawable) {
            val gl = drawable.gl.getGL3()
            if (program != 0) runCatching { gl.glDeleteProgram(program) }
            if (vao != 0) runCatching { gl.glDeleteVertexArrays(1, intArrayOf(vao), 0) }
            if (textures.any { it != 0 }) runCatching { gl.glDeleteTextures(3, textures, 0) }
            program = 0
            vao = 0
            textures.fill(0)
        }

        private fun compile(gl: GL3, type: Int, source: String): Int {
            val shader = gl.glCreateShader(type)
            gl.glShaderSource(shader, 1, arrayOf(source), null)
            gl.glCompileShader(shader)
            val status = IntArray(1)
            gl.glGetShaderiv(shader, GL3.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val info = ByteArray(4096)
                gl.glGetShaderInfoLog(shader, info.size, null, 0, info, 0)
                gl.glDeleteShader(shader)
                error("shader compile failed: ${String(info, Charsets.UTF_8).trimEnd('\u0000')}")
            }
            return shader
        }
    }

    private class PlaneFrame(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
    ) {
        val uvWidth: Int = (width + 1) / 2
        val uvHeight: Int = (height + 1) / 2
    }

    private companion object {
        const val MAX_FREE_FRAMES = 2

        const val VERTEX_SHADER = """
            #version 330 core
            uniform vec2 u_scale;
            out vec2 v_uv;
            void main() {
                vec2 position = vec2(
                    (gl_VertexID == 0 || gl_VertexID == 2) ? -1.0 : 1.0,
                    (gl_VertexID < 2) ? -1.0 : 1.0
                );
                v_uv = vec2((position.x + 1.0) * 0.5, (1.0 - position.y) * 0.5);
                gl_Position = vec4(position * u_scale, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            #version 330 core
            in vec2 v_uv;
            uniform sampler2D u_y;
            uniform sampler2D u_u;
            uniform sampler2D u_v;
            out vec4 color;
            void main() {
                float y = (texture(u_y, v_uv).r - 16.0 / 255.0) * (255.0 / 219.0);
                float u = texture(u_u, v_uv).r - 0.5;
                float v = texture(u_v, v_uv).r - 0.5;
                vec3 rgb = vec3(
                    y + 1.402 * v,
                    y - 0.344136 * u - 0.714136 * v,
                    y + 1.772 * u
                );
                color = vec4(clamp(rgb, 0.0, 1.0), 1.0);
            }
        """
    }
}
