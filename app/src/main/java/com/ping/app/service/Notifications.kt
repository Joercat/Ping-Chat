package com.ping.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.ping.app.MainActivity
import com.ping.app.R
import com.ping.app.data.Settings

/**
 * All notifications in one place.
 *
 * Message text only appears in a notification when the user asked for previews; the lock screen
 * variant can be silenced separately by the system.
 */
class Notifications(private val context: Context, private val settings: Settings) {

    companion object {
        const val SERVICE_CHANNEL = "ping_service"
        const val MESSAGES_CHANNEL = "ping_messages"
        const val SERVICE_NOTIFICATION_ID = 1
        const val REPLY_ACTION = "com.ping.app.REPLY"
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_CONVERSATION = "conversation_id"
        const val EXTRA_PEER = "peer_hash"

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL,
                    "Connection",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Keeps the encrypted pipe open so messages arrive"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    MESSAGES_CHANNEL,
                    "Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "New messages from your contacts"
                    enableVibration(true)
                }
            )
        }
    }

    private val manager = NotificationManagerCompat.from(context)
    private var serviceNotification: Notification? = null

    fun serviceNotification(title: String, text: String): Notification {
        serviceNotification?.let { return it }
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
        serviceNotification = notification
        return notification
    }

    fun updateServiceNotification(title: String, text: String) {
        val current = serviceNotification ?: return
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val updated = NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
        serviceNotification = updated
        try {
            manager.notify(SERVICE_NOTIFICATION_ID, updated)
        } catch (t: SecurityException) {
            // notification permission missing: the service still runs
        }
    }

    fun notifyMessage(conversationId: String, title: String, body: String) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_CONVERSATION, conversationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, conversationId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            action = REPLY_ACTION
            putExtra(EXTRA_CONVERSATION, conversationId)
        }
        val replyPending = PendingIntent.getBroadcast(
            context, conversationId.hashCode() + 7, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_reply, "Reply", replyPending
        ).addRemoteInput(remoteInput).build()

        val markRead = NotificationCompat.Action.Builder(
            0, "Mark read", markReadPending(conversationId)
        ).build()

        val person = Person.Builder().setName(title).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
            .addMessage(body, System.currentTimeMillis(), person)

        val notification = NotificationCompat.Builder(context, MESSAGES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPending)
            .addAction(replyAction)
            .addAction(markRead)
            .setVisibility(
                if (settings.current.notificationPreview) NotificationCompat.VISIBILITY_PRIVATE
                else NotificationCompat.VISIBILITY_SECRET
            )
            .build()
        try {
            manager.notify(conversationId.hashCode(), notification)
        } catch (t: SecurityException) {
            // notifications are not permitted; nothing else to do
        }
    }

    private fun markReadPending(conversationId: String): PendingIntent {
        val intent = Intent(context, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.ACTION_MARK_READ
            putExtra(EXTRA_CONVERSATION, conversationId)
        }
        return PendingIntent.getBroadcast(
            context, conversationId.hashCode() + 13, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun clear(conversationId: String) {
        try {
            manager.cancel(conversationId.hashCode())
        } catch (t: Throwable) {
            // nothing to clear
        }
    }

    fun clearAll() {
        try {
            manager.cancelAll()
        } catch (t: Throwable) {
            // nothing to clear
        }
    }
}
