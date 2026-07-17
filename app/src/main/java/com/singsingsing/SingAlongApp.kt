package com.singsingsing

import android.app.Application
import com.singsingsing.lyrics.LrcLibClient
import com.singsingsing.party.PartyQueueStore
import com.singsingsing.party.PartySession
import com.singsingsing.tidal.LibraryTrackCacheStore
import com.singsingsing.tidal.TidalAuthClient
import com.singsingsing.tidal.TidalCatalogClient
import com.singsingsing.tidal.TidalTokenStore
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
            libraryTrackCache = LibraryTrackCacheStore(this),
        )
        lrcLibClient = LrcLibClient()
        partySession = PartySession(
            tidalCatalog = tidalCatalog,
            lrcLibClient = lrcLibClient,
            queuePersistence = PartyQueueStore(this),
        )
    }

    companion object {
        lateinit var instance: SingAlongApp
            private set
    }
}
