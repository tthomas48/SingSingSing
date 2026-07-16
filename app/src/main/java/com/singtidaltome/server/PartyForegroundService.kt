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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PartyForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: PartyServer? = null
    private var bridge: TidalMediaControllerBridge? = null
    private var guestNotificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
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

        watchForNewGuests()
        val joinUrl = partyServer.joinUrl()
        updateNotification(joinUrl)
        publishJoinUrl(joinUrl)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        guestNotificationJob?.cancel()
        guestNotificationJob = null
        bridge?.stop()
        bridge = null
        server?.stop()
        server = null
        publishJoinUrl(null)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                },
                NotificationChannel(
                    PARTY_EVENTS_CHANNEL_ID,
                    getString(R.string.party_events_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.party_events_channel_desc)
                    enableVibration(true)
                },
            ),
        )
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

    private fun watchForNewGuests() {
        val session = SingAlongApp.instance.partySession
        val detector = NewGuestDetector(session.snapshot.value.guests)
        guestNotificationJob = serviceScope.launch {
            session.snapshot.collectLatest { snapshot ->
                detector.update(snapshot.guests).forEach(::showGuestJoinedNotification)
            }
        }
    }

    private fun showGuestJoinedNotification(guest: com.singtidaltome.party.Guest) {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, JoinActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, PARTY_EVENTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.guest_joined_title))
            .setContentText(getString(R.string.guest_joined_text, guest.name))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(GUEST_NOTIFICATION_ID.incrementAndGet(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "party_server"
        private const val PARTY_EVENTS_CHANNEL_ID = "party_events"
        private const val NOTIFICATION_ID = 42
        private val GUEST_NOTIFICATION_ID = java.util.concurrent.atomic.AtomicInteger(100)

        private val _joinUrl = MutableStateFlow<String?>(null)
        val joinUrl: StateFlow<String?> = _joinUrl.asStateFlow()

        fun publishJoinUrl(url: String?) {
            _joinUrl.value = url
        }

        fun start(context: Context) {
            val intent = Intent(context, PartyForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PartyForegroundService::class.java))
        }
    }
}
