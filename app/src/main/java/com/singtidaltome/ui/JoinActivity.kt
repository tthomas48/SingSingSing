package com.singtidaltome.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.singtidaltome.R
import com.singtidaltome.SingAlongApp
import com.singtidaltome.server.PartyForegroundService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class JoinActivity : AppCompatActivity() {
    private lateinit var joinUrlView: TextView
    private lateinit var bridgeStatusView: TextView
    private lateinit var qrView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_join)

        joinUrlView = findViewById(R.id.joinUrl)
        bridgeStatusView = findViewById(R.id.bridgeStatus)
        qrView = findViewById(R.id.qrCode)

        findViewById<Button>(R.id.notificationAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        PartyForegroundService.start(this)
        latestJoinUrl?.let { showJoinUrl(it) }

        lifecycleScope.launch {
            SingAlongApp.instance.partySession.snapshot.collectLatest { snap ->
                bridgeStatusView.text = when {
                    !hasNotificationAccess() -> getString(R.string.bridge_status_missing_permission)
                    snap.bridgeReady -> getString(R.string.bridge_status_ready)
                    else -> getString(R.string.bridge_status_waiting)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        latestJoinUrl?.let { showJoinUrl(it) }
    }

    private fun showJoinUrl(url: String) {
        joinUrlView.text = url
        qrView.setImageBitmap(renderQr(url))
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat.isNullOrEmpty()) return false
        val expected = ComponentName(this, com.singtidaltome.bridge.TidalNotificationListenerService::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun renderQr(content: String): Bitmap {
        val size = 860
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val fg = ContextCompat.getColor(this, R.color.qr_fg)
        val bg = ContextCompat.getColor(this, R.color.qr_bg)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bits[x, y]) fg else bg)
            }
        }
        return bitmap
    }

    companion object {
        @Volatile
        private var latestJoinUrl: String? = null

        fun updateJoinUrl(url: String) {
            latestJoinUrl = url
        }
    }
}
