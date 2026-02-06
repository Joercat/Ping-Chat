// services/MarkAsReadReceiver.kt
package com.ping.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ping.app.core.network.MessageRouter
import com.ping.app.data.repository.ConversationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MarkAsReadReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var conversationRepository: ConversationRepository
    
    @Inject
    lateinit var notificationService: NotificationService
    
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(NotificationService.EXTRA_CONVERSATION_ID)
        
        conversationId?.let { id ->
            CoroutineScope(Dispatchers.IO).launch {
                conversationRepository.clearUnreadCount(id)
                notificationService.cancelNotification(id)
            }
        }
    }
}
