package com.singtidaltome.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.singtidaltome.BuildConfig
import com.singtidaltome.R
import com.singtidaltome.SingAlongApp
import com.singtidaltome.bridge.BridgeQueueHolder
import com.singtidaltome.bridge.TidalMediaControllerBridge
import com.singtidaltome.bridge.TidalNotificationListenerService
import com.singtidaltome.ui.JoinActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PartyForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: PartyServer? = null
    private var bridge: TidalMediaControllerBridge? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val app = SingAlongApp.instance
        val partyServer = PartyServer(
            context = this,
            partySession = app.partySession,
            scope = serviceScope,
            port = BuildConfig.PARTY_PORT.toInt(),
        )
        partyServer.start()
        server = partyServer

        val mediaBridge = TidalMediaControllerBridge(
            context = this,
            partySession = app.partySession,
            scope = serviceScope,
            listenerComponent = ComponentName(this, TidalNotificationListenerService::class.java),
        )
        app.partySession.attachBridge(mediaBridge)
        BridgeQueueHolder.attach(mediaBridge)
        mediaBridge.start()
        bridge = mediaBridge

        updateNotification(partyServer.joinUrl())
        JoinActivity.updateJoinUrl(partyServer.joinUrl())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        bridge?.stop()
        bridge = null
        server?.stop()
        server = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, JoinActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(url: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(url))
    }

    companion object {
        private const val CHANNEL_ID = "party_server"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, PartyForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PartyForegroundService::class.java))
        }
    }
}
