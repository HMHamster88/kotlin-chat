package org.kotlinchat.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.rtc.ScreenShareSource
import org.kotlinchat.media.android.AndroidScreenCapture

/**
 * Keeps microphone capture (and screen capture) alive while the app is backgrounded.
 *
 * Android 14+ refuses to hand out a MediaProjection unless a foreground service of type
 * `mediaProjection` is already running, so sharing upgrades the running call service.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val client: ChatClient
        get() = (application as ChatApplication).client

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROJECTION -> {
                // Order matters on Android 14+: the foreground service of type mediaProjection
                // must be running before the projection is created, and the consent intent must
                // be available before getDisplayMedia().
                startWith(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                intent.getParcelableExtra<Intent>(EXTRA_PROJECTION)?.let { data ->
                    AndroidScreenCapture.provideProjectionConsent(data)
                    scope.launch {
                        runCatching { client.startScreenShare(ScreenShareSource(0, "Screen", false)) }
                            .onFailure { Log.e(TAG, "screen share failed to start", it) }
                    }
                }
            }

            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> startWith(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
        return START_NOT_STICKY
    }

    private fun startWith(type: Int) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("kotlin-chat")
            .setContentText("In a call")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private val TAG = "CallService"
        private const val CHANNEL_ID = "kotlin-chat-call"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_START = "org.kotlinchat.android.START"
        private const val ACTION_STOP = "org.kotlinchat.android.STOP"
        private const val ACTION_PROJECTION = "org.kotlinchat.android.PROJECTION"
        private const val EXTRA_PROJECTION = "projection"

        fun start(context: Context) {
            val intent = Intent(context, CallService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun startProjection(context: Context, projectionData: Intent) {
            val intent = Intent(context, CallService::class.java)
                .setAction(ACTION_PROJECTION)
                .putExtra(EXTRA_PROJECTION, projectionData)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
