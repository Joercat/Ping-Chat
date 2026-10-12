package com.ping.app

import android.app.Application
import com.ping.app.di.AppGraph
import com.ping.app.service.Notifications
import com.ping.app.service.PingService

/**
 * Holds the one and only [AppGraph].
 *
 * Everything that needs an encrypted database, a socket or the Keystore asks this object. It is
 * created as early as the process allows, because the boot receiver and notification replies come
 * in without any activity being on screen.
 */
class PingApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Decrypted copies exist only so a viewer or a share target can read the file; they never
        // outlive the app run that made them.
        graph.attachments.clearExports()
        Notifications.createChannels(this)
        // Bring the pipe back up on its own after a reboot or an update. The service is
        // best-effort: if the system refuses to start it in the background, the app still
        // works whenever it is open and the next launch tries again.
        if (graph.settings.current.onboardingComplete) {
            PingService.start(this)
        }
    }
}
