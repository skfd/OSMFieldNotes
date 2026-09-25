package com.surveyme.data

import android.content.Context
import com.surveyme.core.PreferencesManager
import com.surveyme.data.database.SurveyMeDatabase
import com.surveyme.data.repository.PoiRepository

object PoiManager {
    private var repository: PoiRepository? = null
    private var fetcher: NearbyFetcher? = null

    @Synchronized
    fun getRepository(context: Context): PoiRepository {
        if (repository == null) {
            val appContext = context.applicationContext
            val database = SurveyMeDatabase.getDatabase(appContext)
            // The classifier labels the logged-in user's own notes "mine"; read the name lazily
            // per store so a login during the session is seen without rebuilding anything.
            val prefs = PreferencesManager(appContext)
            repository = PoiRepository(
                database, appContext,
                ownDisplayName = {
                    (OsmAuthManager.get(appContext).state.value as? OsmAuthState.LoggedIn)?.displayName
                },
                // Read per emission, so a change in the Notes tab reaches the ping gate without a restart.
                fixmeMaxAge = { prefs.fixmeMaxAge }
            )
        }
        return repository!!
    }

    /** The single fetch entry point for notes and fixmes; one per process so its gates are shared. */
    @Synchronized
    fun getFetcher(context: Context): NearbyFetcher {
        if (fetcher == null) {
            fetcher = NearbyFetcher(getRepository(context), PreferencesManager(context.applicationContext))
        }
        return fetcher!!
    }
}