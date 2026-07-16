package com.singtidaltome

import android.app.Application
import com.singtidaltome.lyrics.LrcLibClient
import com.singtidaltome.party.PartySession
import com.singtidaltome.tidal.TidalAuthClient
import com.singtidaltome.tidal.TidalCatalogClient
import com.singtidaltome.tidal.TidalTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SingAlongApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var partySession: PartySession
        private set

    lateinit var tidalAuth: TidalAuthClient
        private set

    lateinit var tidalCatalog: TidalCatalogClient
        private set

    lateinit var lrcLibClient: LrcLibClient
        private set

    lateinit var tokenStore: TidalTokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokenStore = TidalTokenStore(this)
        tidalAuth = TidalAuthClient(
            clientId = BuildConfig.TIDAL_CLIENT_ID,
            clientSecret = BuildConfig.TIDAL_CLIENT_SECRET,
            tokenStore = tokenStore,
        )
        tidalCatalog = TidalCatalogClient(
            authClient = tidalAuth,
            countryCode = BuildConfig.TIDAL_COUNTRY_CODE,
        )
        lrcLibClient = LrcLibClient()
        partySession = PartySession(
            tidalCatalog = tidalCatalog,
            lrcLibClient = lrcLibClient,
        )
    }

    companion object {
        lateinit var instance: SingAlongApp
            private set
    }
}
