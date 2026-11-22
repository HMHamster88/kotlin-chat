package org.kotlinchat.media

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.MediaDevices
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
}
