// services/NotificationService.kt
package com.ping.app.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.ping.app.MainActivity
import com.ping.app.PingApplication
import com.ping.app.R
import com.ping.app.data.models.Message
import com.ping.app.data.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository
) {
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scope = CoroutineScope(Dispatchers.IO)
    
    fun showMessageNotification(message: Message) {
        scope.launch {
            val sender = userRepository.getUserById(message.senderId)
            val senderName = sender?.displayName ?: "Unknown"
            
            val person = Person.Builder()
                .setName(senderName)
                .setKey(message.senderId)
                .build()
            
            // Create reply action
            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel("Reply")
                .build()
            
            val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, message.conversationId)
                putExtra(EXTRA_SENDER_ID, message.senderId)
            }
            
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                message.conversationId.hashCode(),
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_reply,
                "Reply",
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .build()
            
            // Create mark as read action
            val markReadIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, message.conversationId)
            }
            
            val markReadPendingIntent = PendingIntent.getBroadcast(
                context,
                message.conversationId.hashCode() + 1,
                markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val markReadAction = NotificationCompat.Action.Builder(
                R.drawable.ic_check,
                "Mark as read",
                markReadPendingIntent
            ).build()
            
            // Open chat intent
            val contentIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, message.conversationId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                message.conversationId.hashCode() + 2,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Build messaging style
            val messagingStyle = NotificationCompat.MessagingStyle(person)
                .setConversationTitle(if (message.conversationId.contains("group")) "Group Chat" else null)
                .addMessage(
                    getMessageContent(message),
                    message.createdAt,
                    person
                )
            
            val notification = NotificationCompat.Builder(context, PingApplication.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setStyle(messagingStyle)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent)
                .addAction(replyAction)
                .addAction(markReadAction)
                .setGroup(GROUP_MESSAGES)
                .build()
            
            notificationManager.notify(message.conversationId.hashCode(), notification)
            
            // Update summary notification
            updateSummaryNotification()
        }
    }
    
    private fun getMessageContent(message: Message): String {
        return when (message.type) {
            com.ping.app.data.models.MessageType.TEXT -> message.content
            com.ping.app.data.models.MessageType.IMAGE -> "📷 Photo"
            com.ping.app.data.models.MessageType.VIDEO -> "🎥 Video"
            com.ping.app.data.models.MessageType.AUDIO -> "🎵 Audio"
            com.ping.app.data.models.MessageType.FILE -> "📎 File"
            com.ping.app.data.models.MessageType.VOICE_NOTE -> "🎤 Voice message"
            else -> message.content
        }
    }
    
    private fun updateSummaryNotification() {
        val summaryNotification = NotificationCompat.Builder(context, PingApplication.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ping")
            .setContentText("New messages")
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .build()
        
        notificationManager.notify(SUMMARY_ID, summaryNotification)
    }
    
    fun cancelNotification(conversationId: String) {
        notificationManager.cancel(conversationId.hashCode())
    }
    
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
    
    companion object {
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_SENDER_ID = "extra_sender_id"
        private const val GROUP_MESSAGES = "ping_messages_group"
        private const val SUMMARY_ID = 0
    }
}
