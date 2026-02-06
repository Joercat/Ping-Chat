// services/MarkAsReadReceiver.kt
package com.ping.app.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ping.app.data.repository.ConversationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles marking messages as read from notification action.
 */
@AndroidEntryPoint
class MarkAsReadReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var conversationRepository: ConversationRepository
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(NotificationService.EXTRA_CONVERSATION_ID)
        
        if (conversationId.isNullOrBlank()) {
            return
        }
        
        // Cancel the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(conversationId.hashCode())
        
        // Mark as read in database
        scope.launch {
            try {
                conversationRepository.clearUnreadCount(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
