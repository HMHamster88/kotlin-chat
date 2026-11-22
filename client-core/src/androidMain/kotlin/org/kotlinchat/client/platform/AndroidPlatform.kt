package org.kotlinchat.client.platform

import android.content.Context

/**
 * Android has no process-wide working directory, so the application context is handed to the
 * shared client once from `Application.onCreate`.
 */
object AndroidPlatform {
    @Volatile
    private var context: Context? = null

    fun init(application: Context) {
        context = application.applicationContext
    }

    val appContext: Context?
        get() = context
}
