package com.surveyme.core

object Constants {
    const val APP_NAME = "Survey Me"
    const val DATABASE_NAME = "survey_me_database"
    const val PREFERENCES_NAME = "survey_me_preferences"

    const val LOCATION_UPDATE_MIN_TIME = 5000L // 5 seconds
    const val LOCATION_UPDATE_MIN_DISTANCE = 10f // 10 meters

    const val DEFAULT_MAP_ZOOM = 15.0
    const val DEFAULT_MAP_CENTER_LAT = 0.0
    const val DEFAULT_MAP_CENTER_LON = 0.0

    /** Radius around the fetch centre for open notes and fixmes. Becomes a setting in roadmap item 13. */
    const val FETCH_RADIUS_METERS = 2000
    /** Refetch on map load or walker trigger when the last successful fetch is older than this. */
    const val FETCH_STALE_MILLIS = 15 * 60 * 1000L
    /** Minimum gap between two fetches of the same source, walker and map-load triggers. */
    const val FETCH_MIN_INTERVAL_MILLIS = 60 * 1000L
    /** First backoff after a failed fetch; doubles per failure up to the cap. */
    const val FETCH_BACKOFF_BASE_MILLIS = 2 * 60 * 1000L
    const val FETCH_BACKOFF_MAX_MILLIS = 30 * 60 * 1000L
}