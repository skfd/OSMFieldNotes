package com.surveyme.data

import android.content.Context
import com.surveyme.core.PreferencesManager
import com.surveyme.data.database.SurveyMeDatabase
import com.surveyme.data.repository.PoiRepository

object PoiManager {
    private var repository: PoiRepository? = null
    private var fetcher: NearbyFetcher? = null

    fun getRepository(context: Context): PoiRepository {
        if (repository == null) {
            val database = SurveyMeDatabase.getDatabase(context.applicationContext)
            repository = PoiRepository(database, context.applicationContext)
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