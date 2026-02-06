// services/DirectReplyReceiver.kt
package com.ping.app.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.ping.app.R
import com.ping.app.core.encryption.SecureStorage
import com.ping.app.core.network.ConnectionManager
import com.ping.app.core.network.MessageRouter
import com.ping.app.data.models.Message
import com.ping.app.data.models.MessageStatus
import com.ping.app.data.models.MessageType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Handles direct reply from notifications.
 * Note: Using AndroidEntryPoint requires Hilt setup for BroadcastReceiver.
 */
@AndroidEntryPoint
class DirectReplyReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var messageRouter: MessageRouter
    
    @Inject
    lateinit var connectionManager: ConnectionManager
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(NotificationService.KEY_TEXT_REPLY)?.toString()
        
        val conversationId = intent.getStringExtra(NotificationService.EXTRA_CONVERSATION_ID)
        val senderId = intent.getStringExtra(NotificationService.EXTRA_SENDER_ID)
        
        if (replyText.isNullOrBlank() || conversationId.isNullOrBlank() || senderId.isNullOrBlank()) {
            return
        }
        
        // Update notification to show sending status
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val updatedNotification = NotificationCompat.Builder(context, com.ping.app.PingApplication.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ping")
            .setContentText("Sending: $replyText")
            .build()
        
        notificationManager.notify(conversationId.hashCode(), updatedNotification)
        
        // Send the message
        scope.launch {
            try {
                val message = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    senderId = connectionManager.getMyUserId(),
                    receiverId = senderId,
                    content = replyText,
                    type = MessageType.TEXT,
                    status = MessageStatus.SENDING,
                    createdAt = System.currentTimeMillis()
                )
                
                val result = messageRouter.sendMessage(message)
                
                // Update notification based on result
                val statusNotification = NotificationCompat.Builder(context, com.ping.app.PingApplication.CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Ping")
                    .setContentText(if (result.isSuccess) "Message sent" else "Failed to send")
                    .setAutoCancel(true)
                    .build()
                
                notificationManager.notify(conversationId.hashCode(), statusNotification)
                
                // Cancel after a delay
                kotlinx.coroutines.delay(2000)
                notificationManager.cancel(conversationId.hashCode())
                
            } catch (e: Exception) {
                e.printStackTrace()
                
                val errorNotification = NotificationCompat.Builder(context, com.ping.app.PingApplication.CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Ping")
                    .setContentText("Failed to send message")
                    .setAutoCancel(true)
                    .build()
                
                notificationManager.notify(conversationId.hashCode(), errorNotification)
            }
        }
    }
}
