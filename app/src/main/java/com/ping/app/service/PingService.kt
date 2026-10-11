package com.ping.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ping.app.PingApplication
import com.ping.app.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the encrypted pipe open.
 *
 * Messages arrive over a long poll (or straight from a peer on the local network), which has to
 * keep running while the app is in the background — that is exactly what a foreground service is
 * for. The notification is deliberately quiet: it is a status line, not an alert.
 */
class PingService : Service() {

    companion object {
        const val ACTION_START = "com.ping.app.action.START"
        const val ACTION_STOP = "com.ping.app.action.STOP"
        private const val NOTIFICATION_ID = Notifications.SERVICE_NOTIFICATION_ID

        fun start(context: Context) {
            val intent = Intent(context, PingService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // Android may refuse to start a foreground service from the background; the app
                // keeps working whenever it is open, and the next launch tries again.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PingService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var graph: AppGraph

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = (application as PingApplication).graph
        goForeground("Connecting…")
        scope.launch {
            graph.awaitStorage()
            graph.engine.start()
        }
        scope.launch {
            graph.engine.state.collectLatest { state ->
                val text = when {
                    !state.relayConfigured && state.lanPeers == 0 -> "Local network only"
                    !state.relayConfigured -> "Local network · ${state.lanPeers} peer(s)"
                    state.relayReachable -> "Connected" + if (state.queued > 0) " · ${state.queued} queued" else ""
                    else -> "Offline — retrying"
                }
                graph.notifications.updateServiceNotification("Ping", text)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground("Connecting…")
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val notification = graph.notifications.serviceNotification("Ping", text)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            // Missing permission on exotic builds: the service still runs while the app is open.
        }
    }

    override fun onDestroy() {
        graph.engine.stop()
        scope.cancel()
        super.onDestroy()
    }
}
