package com.singsingsing.bridge

import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Enables [android.media.session.MediaSessionManager.getActiveSessions] for this app.
 * We do not process notifications; the service exists for the permission grant.
 */
class TidalNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Notification listener disconnected")
    }

    companion object {
        private const val TAG = "TidalNls"
    }
}
