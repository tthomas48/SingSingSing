package com.singsingsing.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.singsingsing.R
import com.singsingsing.SingAlongApp
import com.singsingsing.bridge.TidalMediaControllerBridge
import com.singsingsing.server.PartyForegroundService
import kotlinx.coroutines.CancellationException
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

        findViewById<Button>(R.id.openTidalButton).setOnClickListener {
            openTidal()
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            showSetupMenu()
        }

        PartyForegroundService.start(this)

        lifecycleScope.launch {
            PartyForegroundService.joinUrl.collectLatest { url ->
                if (url.isNullOrBlank()) {
                    joinUrlView.text = getString(R.string.join_waiting)
                } else {
                    showJoinUrl(url)
                }
            }
        }

        lifecycleScope.launch {
            SingAlongApp.instance.partySession.snapshot.collectLatest { snap ->
                val bridgeStatus = when {
                    !hasNotificationAccess() -> getString(R.string.bridge_status_missing_permission)
                    snap.bridgeReady -> getString(R.string.bridge_status_ready)
                    else -> getString(R.string.bridge_status_waiting)
                }
                val library = snap.libraryPlaylistName?.let { " · library: $it" }.orEmpty()
                bridgeStatusView.text = "$bridgeStatus · ${snap.guests.size} joined$library"
            }
        }

        lifecycleScope.launch {
            runCatching {
                if (SingAlongApp.instance.tidalCatalog.isLibraryConfigured()) {
                    SingAlongApp.instance.tidalCatalog.getLibraryTracks()
                    SingAlongApp.instance.partySession.refreshLibrarySnapshot()
                }
            }
        }
    }

    private fun showJoinUrl(url: String) {
        joinUrlView.text = url
        qrView.setImageBitmap(renderQr(url))
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat.isNullOrEmpty()) return false
        val expected = ComponentName(this, com.singsingsing.bridge.TidalNotificationListenerService::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun openTidal() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            setClassName(
                TidalMediaControllerBridge.TIDAL_PACKAGE,
                TidalMediaControllerBridge.TIDAL_TV_LAUNCHER,
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                packageManager.getLeanbackLaunchIntentForPackage(
                    TidalMediaControllerBridge.TIDAL_PACKAGE,
                )?.let(::startActivity)
            }
    }

    private fun showSetupMenu() {
        val signedIn = SingAlongApp.instance.tidalAuth.hasUserSession()
        val options = buildList {
            add(getString(R.string.open_notification_access))
            add(getString(R.string.open_accessibility))
            add(getString(R.string.enable_party_notifications))
            add(
                if (signedIn) {
                    getString(R.string.sign_in_tidal_again)
                } else {
                    getString(R.string.sign_in_tidal)
                },
            )
            if (signedIn) {
                add(getString(R.string.choose_library_playlist))
            }
        }.toTypedArray()

        // Do not call setMessage() here — Android AlertDialog drops setItems when a message is set.
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.open_notification_access) ->
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    getString(R.string.open_accessibility) ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    getString(R.string.enable_party_notifications) ->
                        requestPartyNotifications()
                    getString(R.string.sign_in_tidal), getString(R.string.sign_in_tidal_again) ->
                        startTidalPkceLogin()
                    getString(R.string.choose_library_playlist) ->
                        chooseLibraryPlaylist()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startTidalPkceLogin() {
        val auth = SingAlongApp.instance.tidalAuth
        if (!auth.isConfigured()) {
            toast("Set TIDAL_CLIENT_ID / TIDAL_CLIENT_SECRET in local.properties")
            return
        }
        val joinUrl = PartyForegroundService.joinUrl.value
        if (joinUrl.isNullOrBlank()) {
            toast("Party server is still starting — try again in a moment")
            return
        }
        val redirectUri = joinUrl.trimEnd('/') + "/oauth/callback"
        try {
            val session = auth.beginPkceLogin(redirectUri)
            val message = getString(
                R.string.tidal_login_pkce_message,
                session.authorizeUrl,
                redirectUri,
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.tidal_login_title)
                .setMessage(message)
                .setPositiveButton(R.string.show_login_qr) { _, _ ->
                    showLoginQr(session.authorizeUrl, redirectUri)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (error: Throwable) {
            toast(getString(R.string.tidal_login_failed, error.message ?: "unknown error"))
        }
    }

    private fun showLoginQr(authorizeUrl: String, redirectUri: String) {
        val image = ImageView(this).apply {
            setImageBitmap(renderQr(authorizeUrl))
            setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tidal_login_title)
            .setMessage(getString(R.string.tidal_login_qr_hint, redirectUri))
            .setView(image)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun chooseLibraryPlaylist() {
        val catalog = SingAlongApp.instance.tidalCatalog
        val auth = SingAlongApp.instance.tidalAuth
        if (!auth.hasUserSession()) {
            toast("Sign in to Tidal first")
            return
        }
        toast("Loading playlists… (see logcat tag TidalApi)")
        lifecycleScope.launch {
            try {
                android.util.Log.i(
                    "TidalApi",
                    "chooseLibraryPlaylist start hasSession=${auth.hasUserSession()} " +
                        "cachedUserId=${auth.savedUserId()} libraryId=${auth.libraryPlaylistId()}",
                )
                val playlists = catalog.listUserPlaylists()
                android.util.Log.i("TidalApi", "chooseLibraryPlaylist got ${playlists.size} playlists")
                if (playlists.isEmpty()) {
                    toast(getString(R.string.choose_playlist_empty))
                    return@launch
                }
                val labels = playlists.map { "${it.name} (${it.numberOfItems})" }.toTypedArray()
                AlertDialog.Builder(this@JoinActivity)
                    .setTitle(R.string.choose_playlist_title)
                    .setItems(labels) { _, which ->
                        val chosen = playlists[which]
                        android.util.Log.i(
                            "TidalApi",
                            "Selected library playlist id=${chosen.id} name=${chosen.name} items=${chosen.numberOfItems}",
                        )
                        auth.saveLibraryPlaylist(chosen.id, chosen.name)
                        catalog.invalidateLibraryCache()
                        lifecycleScope.launch {
                            runCatching { catalog.getLibraryTracks(forceRefresh = true) }
                                .onFailure { error ->
                                    android.util.Log.e("TidalApi", "Failed loading library tracks", error)
                                    toast(error.message ?: "Failed loading library tracks")
                                }
                            SingAlongApp.instance.partySession.refreshLibrarySnapshot()
                        }
                        toast(getString(R.string.playlist_selected, chosen.name))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                android.util.Log.e("TidalApi", "chooseLibraryPlaylist failed", error)
                toast(error.message ?: "Could not load playlists")
            }
        }
    }

    private fun requestPartyNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        } else {
            toast("Party notifications already enabled")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
}
