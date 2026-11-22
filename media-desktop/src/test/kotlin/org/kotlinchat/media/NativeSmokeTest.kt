package org.kotlinchat.media

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer
import kotlin.test.Test

/**
 * De-risks the two things that can fail before any of our code matters: loading the JNI
 * library and opening the platform audio devices.
 */
class NativeSmokeTest {

    @Test
    fun `native library loads and audio devices are enumerable`() {
        val factory = PeerConnectionFactory()
        try {
            val capture = MediaDevices.getAudioCaptureDevices()
            val render = MediaDevices.getAudioRenderDevices()

            println("capture devices: " + capture.joinToString { "${it.name} [${it.descriptor}]" })
            println("render devices:  " + render.joinToString { "${it.name} [${it.descriptor}]" })
        } finally {
            factory.dispose()
        }
    }

    @Test
    fun `screen and window sources are enumerable`() {
        val screens = ScreenCapturer()
        try {
            println("screens: " + screens.desktopSources.joinToString { "${it.id}:${it.title}" })
        } finally {
            screens.dispose()
        }

        val windows = WindowCapturer()
        try {
            println("windows: " + windows.desktopSources.take(5).joinToString { "${it.id}:${it.title}" })
        } finally {
            windows.dispose()
        }
    }
}
