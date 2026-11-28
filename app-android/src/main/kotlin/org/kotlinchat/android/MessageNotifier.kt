package org.kotlinchat.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.notification.MessageNotification
import org.kotlinchat.client.notification.MessageNotifications
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.protocol.ChatMessage

/**
 * Shows one updated conversation notification for the connected room while the app is in the
 * background. Application-scoped because [ChatClient] lives there and keeps the WebSocket open
 * even when the activity is gone.
 */
class MessageNotifier(
    private val context: Context,
    private val client: ChatClient,
) {

    private val manager = NotificationManagerCompat.from(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recent = ArrayDeque<ChatMessage>()
    private var lastRoomId: String? = null

    @Volatile
    private var settings = ClientSettings()

    @Volatile
    private var foreground = false

    init {
        ensureChannel()
        scope.launch {
            client.incomingMessages.collect { onMessage(it) }
        }
    }

    /** Kept in step with the Compose settings; called on the main thread. */
    fun updateSettings(newSettings: ClientSettings) {
        val previewChanged = settings.notifyPreview != newSettings.notifyPreview
        settings = newSettings
        if (!newSettings.notifyOnMessage || previewChanged) {
            recent.clear()
            cancel()
        }
    }

    /** The activity resumed or paused. Coming back cancels anything left in the shade. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            recent.clear()
            lastRoomId = null
            cancel()
        }
    }

    fun close() {
        scope.cancel()
        cancel()
    }

    private fun onMessage(message: ChatMessage) {
        if (foreground) return
        val snapshot = client.snapshot.value
        val notification = MessageNotifications.build(
            snapshot = snapshot,
            message = message,
            settings = settings,
            appActive = foreground,
        ) ?: return
        if (snapshot.roomId != lastRoomId) {
            recent.clear()
            lastRoomId = snapshot.roomId
        }
        recent.addLast(message)
        while (recent.size > MAX_RECENT) recent.removeFirst()
        show(snapshot, message, notification)
    }

    private fun show(snapshot: ChatSnapshot, message: ChatMessage, latest: MessageNotification) {
        if (!canPost()) return
        val roomName = snapshot.room?.room?.name?.trim().orEmpty().ifBlank { "Kotlin Chat" }
        val selfName = snapshot.self?.name?.trim().orEmpty().ifBlank { "You" }
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName(selfName).setKey(snapshot.self?.sessionId).build(),
        )
        for (m in recent) {
            style.addMessage(
                MessageNotifications.body(m.text, settings.notifyPreview),
                m.ts,
                Person.Builder().setName(m.name.trim().ifBlank { "Someone" }).setKey(m.from).build(),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(roomName)
            .setContentText("${latest.title}: ${latest.body}")
            .setStyle(style)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(true)
            .setWhen(message.ts)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (canPost()) manager.notify(NOTIFICATION_ID, notification)
    }

    private fun canPost(): Boolean {
        if (!settings.notifyOnMessage || foreground) return false
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "New chat messages"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "messages"
        const val NOTIFICATION_ID = 8
        const val MAX_RECENT = 5
    }
}
