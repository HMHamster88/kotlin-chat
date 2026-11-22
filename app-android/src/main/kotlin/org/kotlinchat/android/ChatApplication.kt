package org.kotlinchat.android

import android.app.Application
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.platform.AndroidPlatform
import org.kotlinchat.media.android.AndroidRtcEngine

class ChatApplication : Application() {

    lateinit var client: ChatClient
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.init(this)
        client = ChatClient(AndroidRtcEngine())
    }
}
