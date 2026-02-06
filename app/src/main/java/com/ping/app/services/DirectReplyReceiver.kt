// services/DirectReplyReceiver.kt
package com.ping.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.ping.app.core.network.ConnectionManager
import com.ping.app.core.network.MessageRouter
import com.ping.app.data.models.Message
import com.ping.app.data.models.MessageType
import com.ping.app.data.repository.ConversationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class DirectReplyReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var messageRouter: MessageRouter
    
    @Inject
    lateinit var conversationRepository: ConversationRepository
    
    @Inject
    lateinit var connectionManager: ConnectionManager
    
    @Inject
    lateinit var notificationService: NotificationService
    
    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(NotificationService.KEY_TEXT_REPLY)?.toString()
        
        val conversationId = intent.getStringExtra(NotificationService.EXTRA_CONVERSATION_ID)
        val senderId = intent.getStringExtra(NotificationService.EXTRA_SENDER_ID)
        
        if (replyText != null && conversationId != null && senderId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val message = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    senderId = connectionManager.getMyUserId(),
                    receiverId = senderId,
                    content = replyText,
                    type = MessageType.TEXT
                )
                
                messageRouter.sendMessage(message)
                
                // Cancel the notification
                notificationService.cancelNotification(conversationId)
            }
        }
    }
}
