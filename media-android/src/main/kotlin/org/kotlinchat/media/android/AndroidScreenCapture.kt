package org.kotlinchat.media.android

import android.content.Intent
import com.shepeliev.webrtckmp.MediaProjectionIntentHolder

/**
 * Android requires the app to hand the MediaProjection consent result to the capture layer
 * before `getDisplayMedia()` is called. This keeps the third-party holder out of the app module.
 */
object AndroidScreenCapture {

    fun provideProjectionConsent(intent: Intent) {
        MediaProjectionIntentHolder.intent = intent
    }
}
