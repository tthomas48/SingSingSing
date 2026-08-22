package com.singsingsing.server

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.singsingsing.BuildConfig
import com.singsingsing.R
import com.singsingsing.SingAlongApp
import com.singsingsing.bridge.BridgeQueueHolder
import com.singsingsing.bridge.TidalMediaControllerBridge
import com.singsingsing.bridge.TidalNotificationListenerService
import com.singsingsing.net.LanMonitor
import com.singsingsing.net.PartyLanState
import com.singsingsing.ui.JoinActivity
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
    private var lanMonitor: LanMonitor? = null
    private var lanWatchJob: Job? = null
    private var guestNotificationJob: Job? = null
    private var messageToastJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        val monitor = LanMonitor(this)
        monitor.start()
        lanMonitor = monitor

        val partyServer = PartyServer(
            context = this,
            partySession = app.partySession,
            scope = serviceScope,
            port = BuildConfig.PARTY_PORT.toInt(),
            lanState = { monitor.state.value },
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
        watchForNewMessages()
        watchLan(monitor)
        publishLan(monitor.state.value)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        guestNotificationJob?.cancel()
        guestNotificationJob = null
        messageToastJob?.cancel()
        messageToastJob = null
        lanWatchJob?.cancel()
        lanWatchJob = null
        lanMonitor?.stop()
        lanMonitor = null
        bridge?.stop()
        bridge = null
        server?.stop()
        server = null
        publishJoinUrl(null)
        publishLanState(null)
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

    private fun watchLan(monitor: LanMonitor) {
        lanWatchJob = serviceScope.launch {
            monitor.state.collectLatest(::publishLan)
        }
    }

    private fun publishLan(state: PartyLanState) {
        val url = state.joinUrl(BuildConfig.PARTY_PORT.toInt())
        updateNotification(url)
        publishJoinUrl(url)
        publishLanState(state)
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

    private fun watchForNewMessages() {
        val session = SingAlongApp.instance.partySession
        val detector = NewPartyMessageDetector(session.snapshot.value.messages)
        messageToastJob = serviceScope.launch {
            session.snapshot.collectLatest { snapshot ->
                detector.update(snapshot.messages)
                    .asReversed()
                    .forEach { showMessageToast(it.text) }
            }
        }
    }

    private fun showMessageToast(text: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun showGuestJoinedNotification(guest: com.singsingsing.party.Guest) {
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

        private val _lanState = MutableStateFlow<PartyLanState?>(null)
        val lanState: StateFlow<PartyLanState?> = _lanState.asStateFlow()

        fun publishJoinUrl(url: String?) {
            _joinUrl.value = url
        }

        fun publishLanState(state: PartyLanState?) {
            _lanState.value = state
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
