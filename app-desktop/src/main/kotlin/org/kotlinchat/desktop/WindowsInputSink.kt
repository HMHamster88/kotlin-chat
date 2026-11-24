package org.kotlinchat.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Rectangle
import java.awt.event.KeyEvent
import kotlin.math.roundToInt
import org.kotlinchat.client.control.RemoteInputSink
import org.kotlinchat.client.control.RemoteMouseButton
import org.kotlinchat.client.rtc.ScreenShareSource

/**
 * Windows host-side input injection through `SendInput`.
 *
 * Coordinates travel as normalized values inside the shared monitor. Only screen shares are
 * accepted; a window share would need client-rect and focus handling that is deliberately out of
 * scope for v1.
 */
class WindowsInputSink : RemoteInputSink {

    private val lock = Any()
    private val pressedButtons = mutableSetOf<RemoteMouseButton>()
    private val pressedKeys = mutableSetOf<Int>()
    private var sourceRect: Rectangle? = null
    private var altDown = false
    private var blockedF4 = false

    override fun setSource(source: ScreenShareSource?) {
        synchronized(lock) {
            releaseAllLocked()
            sourceRect = source
                ?.takeUnless { it.isWindow }
                ?.let { share ->
                    val monitors = monitorRects()
                    monitors.getOrNull(share.id.toInt()) ?: monitors.firstOrNull()
                }
        }
    }

    override fun movePointer(x: Float, y: Float) {
        val rect = synchronized(lock) { sourceRect } ?: return
        val px = rect.x + x.coerceIn(0f, 1f) * rect.width
        val py = rect.y + y.coerceIn(0f, 1f) * rect.height
        val (ax, ay) = absolute(px.roundToInt(), py.roundToInt())
        sendMouse(
            MOUSEEVENTF_MOVE or MOUSEEVENTF_ABSOLUTE or MOUSEEVENTF_VIRTUALDESK,
            ax,
            ay,
            0,
        )
    }

    override fun mouseButton(button: RemoteMouseButton, down: Boolean) {
        val flag = when (button) {
            RemoteMouseButton.LEFT -> if (down) MOUSEEVENTF_LEFTDOWN else MOUSEEVENTF_LEFTUP
            RemoteMouseButton.MIDDLE -> if (down) MOUSEEVENTF_MIDDLEDOWN else MOUSEEVENTF_MIDDLEUP
            RemoteMouseButton.RIGHT -> if (down) MOUSEEVENTF_RIGHTDOWN else MOUSEEVENTF_RIGHTUP
        }
        synchronized(lock) {
            if (down) pressedButtons += button else pressedButtons -= button
        }
        sendMouse(flag, 0, 0, 0)
    }

    override fun wheel(delta: Int, horizontal: Boolean) {
        if (delta == 0) return
        // AWT reports positive as scroll-down; Win32 treats positive wheel data as scroll-up.
        val amount = (-delta * WHEEL_DELTA)
        sendMouse(
            if (horizontal) MOUSEEVENTF_HWHEEL else MOUSEEVENTF_WHEEL,
            0,
            0,
            amount.toLong() and 0xffffffffL,
        )
    }

    override fun key(keyCode: Int, down: Boolean) {
        val vk = awtToVk(keyCode) ?: return
        synchronized(lock) {
            if (vk == VK_MENU) altDown = down
            if (vk == VK_F4) {
                if (altDown) {
                    // Only Alt+F4 is suppressed. Release the flag on the matching key-up so a
                    // later plain F4 works normally.
                    if (!down) blockedF4 = false else blockedF4 = true
                    return
                }
                if (!down && blockedF4) {
                    blockedF4 = false
                    return
                }
            }
            if (down) pressedKeys += vk else pressedKeys -= vk
        }
        sendKey(vk, down)
    }

    override fun releaseAll() {
        synchronized(lock) { releaseAllLocked() }
    }

    private fun releaseAllLocked() {
        val buttons = pressedButtons.toList()
        pressedButtons.clear()
        buttons.forEach { button ->
            val flag = when (button) {
                RemoteMouseButton.LEFT -> MOUSEEVENTF_LEFTUP
                RemoteMouseButton.MIDDLE -> MOUSEEVENTF_MIDDLEUP
                RemoteMouseButton.RIGHT -> MOUSEEVENTF_RIGHTUP
            }
            sendMouse(flag, 0, 0, 0)
        }
        val keys = pressedKeys.toList()
        pressedKeys.clear()
        keys.forEach { vk -> sendKey(vk, false) }
        altDown = false
        blockedF4 = false
    }

    private fun sendMouse(flags: Int, dx: Int, dy: Int, data: Long) {
        runCatching {
            val input = WinUser.INPUT()
            input.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
            input.input.setType("mi")
            val mouse = input.input.mi
            mouse.dx = WinDef.LONG(dx.toLong())
            mouse.dy = WinDef.LONG(dy.toLong())
            mouse.mouseData = WinDef.DWORD(data)
            mouse.dwFlags = WinDef.DWORD(flags.toLong())
            mouse.time = WinDef.DWORD(0)
            User32.INSTANCE.SendInput(WinDef.DWORD(1), arrayOf(input), input.size())
        }
    }

    private fun sendKey(vk: Int, down: Boolean) {
        runCatching {
            var flags = if (down) 0 else WinUser.KEYBDINPUT.KEYEVENTF_KEYUP
            if (vk in EXTENDED_KEYS) flags = flags or WinUser.KEYBDINPUT.KEYEVENTF_EXTENDEDKEY
            val input = WinUser.INPUT()
            input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
            input.input.setType("ki")
            val key = input.input.ki
            key.wVk = WinDef.WORD(vk.toLong())
            key.wScan = WinDef.WORD(0)
            key.dwFlags = WinDef.DWORD(flags.toLong())
            key.time = WinDef.DWORD(0)
            User32.INSTANCE.SendInput(WinDef.DWORD(1), arrayOf(input), input.size())
        }
    }

    private fun absolute(x: Int, y: Int): Pair<Int, Int> {
        val vx = User32.INSTANCE.GetSystemMetrics(WinUser.SM_XVIRTUALSCREEN)
        val vy = User32.INSTANCE.GetSystemMetrics(WinUser.SM_YVIRTUALSCREEN)
        val vw = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXVIRTUALSCREEN).coerceAtLeast(1)
        val vh = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYVIRTUALSCREEN).coerceAtLeast(1)
        val ax = ((x - vx) * 65535.0 / (vw - 1)).roundToInt().coerceIn(0, 65535)
        val ay = ((y - vy) * 65535.0 / (vh - 1)).roundToInt().coerceIn(0, 65535)
        return ax to ay
    }

    private fun monitorRects(): List<Rectangle> {
        val result = mutableListOf<Rectangle>()
        val callback = object : WinUser.MONITORENUMPROC {
            override fun apply(
                hMonitor: WinUser.HMONITOR,
                hdc: WinDef.HDC?,
                rect: WinDef.RECT?,
                data: WinDef.LPARAM?,
            ): Int {
                val info = WinUser.MONITORINFO()
                info.cbSize = info.size()
                if (User32.INSTANCE.GetMonitorInfo(hMonitor, info).booleanValue()) {
                    val monitor = info.rcMonitor
                    result += Rectangle(
                        monitor.left,
                        monitor.top,
                        monitor.right - monitor.left,
                        monitor.bottom - monitor.top,
                    )
                }
                return 1
            }
        }
        runCatching { User32.INSTANCE.EnumDisplayMonitors(null, null, callback, null) }
        return result
    }

    private companion object {
        const val MOUSEEVENTF_MOVE = 0x0001
        const val MOUSEEVENTF_LEFTDOWN = 0x0002
        const val MOUSEEVENTF_LEFTUP = 0x0004
        const val MOUSEEVENTF_RIGHTDOWN = 0x0008
        const val MOUSEEVENTF_RIGHTUP = 0x0010
        const val MOUSEEVENTF_MIDDLEDOWN = 0x0020
        const val MOUSEEVENTF_MIDDLEUP = 0x0040
        const val MOUSEEVENTF_WHEEL = 0x0800
        const val MOUSEEVENTF_HWHEEL = 0x1000
        const val MOUSEEVENTF_VIRTUALDESK = 0x4000
        const val MOUSEEVENTF_ABSOLUTE = 0x8000
        const val WHEEL_DELTA = 120
        const val VK_MENU = 0x12
        const val VK_F4 = 0x73
        val EXTENDED_KEYS = setOf(
            0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, // page/end/home/arrows
            0x2D, 0x2E, // insert, delete
            0x5B, 0x5C, // Win keys
            0x5D, // context menu
        )

        fun awtToVk(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.VK_ENTER -> 0x0D
            KeyEvent.VK_BACK_SPACE -> 0x08
            KeyEvent.VK_TAB -> 0x09
            KeyEvent.VK_ESCAPE -> 0x1B
            KeyEvent.VK_DELETE -> 0x2E
            KeyEvent.VK_INSERT -> 0x2D
            KeyEvent.VK_META, KeyEvent.VK_WINDOWS -> 0x5B
            KeyEvent.VK_CONTEXT_MENU -> 0x5D
            0 -> null
            else -> keyCode.takeIf { it in 1..0xFF }
        }
    }
}

/** Null on non-Windows hosts, which keeps the client from advertising the capability. */
fun createRemoteInputSink(): RemoteInputSink? =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
        runCatching { WindowsInputSink() }.getOrNull()
    } else {
        null
    }
