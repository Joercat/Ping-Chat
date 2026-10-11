package com.ping.app.service

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ping.app.PingApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Reply" and "Mark read" from a notification, without opening the app.
 *
 * The work happens in a coroutine that is given just enough time by [goAsync]; sending is
 * best-effort, and a message that cannot go out immediately lands in the encrypted outbox.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(Notifications.EXTRA_CONVERSATION) ?: return
        val application = context.applicationContext as? PingApplication ?: return
        val graph = application.graph

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                graph.awaitStorage()
                val conversation = graph.onDb { graph.store.conversation(conversationId) }
                if (conversation == null) {
                    graph.notifications.clear(conversationId)
                    return@launch
                }
                when (intent.action) {
                    Notifications.REPLY_ACTION -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(Notifications.KEY_REPLY_TEXT)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (text.isNotEmpty()) {
                            graph.engine.sendText(conversation.peerHash, text)
                        }
                        graph.notifications.clear(conversationId)
                    }
                    ACTION_MARK_READ -> {
                        graph.engine.markRead(conversation.peerHash, conversationId)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Notification action failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "com.ping.app.action.MARK_READ"
        private const val TAG = "PingReply"
    }
}

/** Brings the connection back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val application = context.applicationContext as? PingApplication ?: return
        val graph = application.graph
        if (!graph.settings.current.onboardingComplete) return
        PingService.start(context)
    }
}
