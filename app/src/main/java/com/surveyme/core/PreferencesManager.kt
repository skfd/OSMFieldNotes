package com.surveyme.core

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        Constants.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_MAP_STYLE = "map_style"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_TRACKING_ACTIVE = "tracking_active"
        private const val KEY_LAST_LATITUDE = "last_latitude"
        private const val KEY_LAST_LONGITUDE = "last_longitude"
        private const val KEY_LAST_ZOOM = "last_zoom"
        private const val KEY_DRAW_TRACK = "draw_track"
        private const val KEY_FETCH_PREFIX = "fetch_centre_"
        private const val KEY_CLASSIFIER_VERSION = "classifier_version"
        private const val KEY_NOTES_AUDIENCE_FILTER = "notes_audience_filter"
        private const val KEY_LAST_TRIAGE_AT = "last_triage_at"
        private const val KEY_FIXME_MAX_AGE = "fixme_max_age"
        private const val KEY_FETCH_RADIUS = "fetch_radius_m"
        private const val KEY_PING_RADIUS = "ping_radius_m"
        private const val KEY_PING_CLASSES = "ping_classes"
        private const val KEY_MUTE_NOTES = "mute_notes"
        private const val KEY_MUTE_FIXMES = "mute_fixmes"
        private const val KEY_QUIET_ENABLED = "quiet_hours_enabled"
        private const val KEY_QUIET_START = "quiet_hours_start"
        private const val KEY_QUIET_END = "quiet_hours_end"

        /** The Notes tab filter value that shows everything; the others are [NoteLabels.AUDIENCES]. */
        const val AUDIENCE_FILTER_ALL = "all"

        /** Fetch radius choices (item 13), metres. */
        val FETCH_RADIUS_CHOICES = listOf(1000, 2000, 3000, 5000)
        /** Ping radius choices (item 13), metres; the default matches [com.surveyme.data.model.Poi.notificationRadius]. */
        val PING_RADIUS_CHOICES = listOf(25, 50, 100, 200)
        const val DEFAULT_PING_RADIUS_METERS = 50
        /** Quiet hours default window: 22:00 to 07:00, as minutes of the day. */
        const val DEFAULT_QUIET_START = 22 * 60
        const val DEFAULT_QUIET_END = 7 * 60

        private val _loopSettingsTick = MutableStateFlow(0)

        /**
         * Bumped on every write of a loop setting (item 13). All instances share one
         * SharedPreferences file, so one process-wide tick is enough; the repository's ping
         * flow and the Notes tab combine with it and re-read the prefs on each emission.
         */
        val loopSettingsTick: StateFlow<Int> = _loopSettingsTick.asStateFlow()

        private fun bumpLoopSettings() {
            _loopSettingsTick.value = _loopSettingsTick.value + 1
        }
    }

    // Loop settings (item 13). Each write bumps [loopSettingsTick].

    /** How far around the walker (or the map centre) notes and fixmes are fetched, metres. */
    var fetchRadiusMeters: Int
        get() = prefs.getInt(KEY_FETCH_RADIUS, Constants.FETCH_RADIUS_METERS)
        set(value) {
            prefs.edit().putInt(KEY_FETCH_RADIUS, value).apply()
            bumpLoopSettings()
        }

    /** How close a note or fixme must be to ping, metres. Imported waypoints keep their own radius. */
    var pingRadiusMeters: Int
        get() = prefs.getInt(KEY_PING_RADIUS, DEFAULT_PING_RADIUS_METERS)
        set(value) {
            prefs.edit().putInt(KEY_PING_RADIUS, value).apply()
            bumpLoopSettings()
        }

    /**
     * Which note classes may ping: a subset of [NoteLabels.AUDIENCES] plus
     * [PingSettings.UNCLASSIFIED] for notes carrying no audience label. Default: surveyor only.
     * An empty set is a real choice (no note pings) and is stored as such.
     */
    var pingClasses: Set<String>
        get() = prefs.getStringSet(KEY_PING_CLASSES, null)?.toSet() ?: PingSettings.DEFAULT_CLASSES
        set(value) {
            // A fresh copy: the set handed to putStringSet must never be one we later mutate.
            prefs.edit().putStringSet(KEY_PING_CLASSES, value.toHashSet()).apply()
            bumpLoopSettings()
        }

    /** Master switch: no note pings at all. Lists and map are unaffected. */
    var muteNotes: Boolean
        get() = prefs.getBoolean(KEY_MUTE_NOTES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MUTE_NOTES, value).apply()
            bumpLoopSettings()
        }

    /** Master switch: no fixme pings at all. Lists and map are unaffected. */
    var muteFixmes: Boolean
        get() = prefs.getBoolean(KEY_MUTE_FIXMES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MUTE_FIXMES, value).apply()
            bumpLoopSettings()
        }

    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_QUIET_ENABLED, value).apply()
            bumpLoopSettings()
        }

    /** Quiet hours start, minutes after local midnight (0..1439). */
    var quietHoursStart: Int
        get() = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START)
        set(value) {
            prefs.edit().putInt(KEY_QUIET_START, value).apply()
            bumpLoopSettings()
        }

    /** Quiet hours end, minutes after local midnight (0..1439); before the start means the window wraps past midnight. */
    var quietHoursEnd: Int
        get() = prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END)
        set(value) {
            prefs.edit().putInt(KEY_QUIET_END, value).apply()
            bumpLoopSettings()
        }

    /** The ping gate's inputs in one read, for [NotePingPolicy]. */
    val pingSettings: PingSettings
        get() = PingSettings(
            classes = pingClasses,
            muteNotes = muteNotes,
            muteFixmes = muteFixmes,
            fixmeMaxAge = fixmeMaxAge
        )

    /** The quiet-hours window as the pure [QuietHours] sees it; null when disabled. */
    val quietHours: QuietHours.Window?
        get() = if (quietHoursEnabled) QuietHours.Window(quietHoursStart, quietHoursEnd) else null

    /**
     * The [NoteClassifier.VERSION] whose rules the stored labels were computed with; 0 before
     * item 8. A lower value than the code's triggers one reclassification of all notes.
     */
    var classifierVersion: Int
        get() = prefs.getInt(KEY_CLASSIFIER_VERSION, 0)
        set(value) {
            prefs.edit().putInt(KEY_CLASSIFIER_VERSION, value).apply()
        }

    /** The audience chip selected in the Notes tab: [AUDIENCE_FILTER_ALL] or one of [NoteLabels.AUDIENCES]. */
    var notesAudienceFilter: String
        get() = prefs.getString(KEY_NOTES_AUDIENCE_FILTER, AUDIENCE_FILTER_ALL) ?: AUDIENCE_FILTER_ALL
        set(value) {
            prefs.edit().putString(KEY_NOTES_AUDIENCE_FILTER, value).apply()
        }

    /**
     * When the triage screen was last opened and then acted on or closed with Done (item 9), in
     * epoch millis; [NoteTriage.NEVER] (0) before the first time. Notes created after this are "new".
     */
    var lastTriageAt: Long
        get() = prefs.getLong(KEY_LAST_TRIAGE_AT, NoteTriage.NEVER)
        set(value) {
            prefs.edit().putLong(KEY_LAST_TRIAGE_AT, value).apply()
        }

    /**
     * How old a fixme may be (by the element's last edit) and still be listed or ping (item 12).
     * Set from the Notes tab's overflow menu or the Settings screen (item 13): one pref, two doors.
     */
    var fixmeMaxAge: FixmeMaxAge
        get() = FixmeMaxAge.fromPrefValue(prefs.getString(KEY_FIXME_MAX_AGE, null))
        set(value) {
            prefs.edit().putString(KEY_FIXME_MAX_AGE, value.prefValue).apply()
            bumpLoopSettings()
        }

    /** Where and when a source was last fetched successfully. */
    data class FetchCentre(val latitude: Double, val longitude: Double, val radiusMeters: Int, val timeMillis: Long)

    fun saveFetchCentre(source: String, centre: FetchCentre) {
        prefs.edit().apply {
            putLong("$KEY_FETCH_PREFIX${source}_lat", java.lang.Double.doubleToRawLongBits(centre.latitude))
            putLong("$KEY_FETCH_PREFIX${source}_lon", java.lang.Double.doubleToRawLongBits(centre.longitude))
            putInt("$KEY_FETCH_PREFIX${source}_radius", centre.radiusMeters)
            putLong("$KEY_FETCH_PREFIX${source}_time", centre.timeMillis)
            apply()
        }
    }

    fun getFetchCentre(source: String): FetchCentre? {
        if (!prefs.contains("$KEY_FETCH_PREFIX${source}_time")) return null
        return FetchCentre(
            latitude = java.lang.Double.longBitsToDouble(prefs.getLong("$KEY_FETCH_PREFIX${source}_lat", 0L)),
            longitude = java.lang.Double.longBitsToDouble(prefs.getLong("$KEY_FETCH_PREFIX${source}_lon", 0L)),
            radiusMeters = prefs.getInt("$KEY_FETCH_PREFIX${source}_radius", Constants.FETCH_RADIUS_METERS),
            timeMillis = prefs.getLong("$KEY_FETCH_PREFIX${source}_time", 0L)
        )
    }

    var isDarkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DARK_MODE, value).apply()
            applyDarkMode(value)
            Timber.d("Dark mode set to: $value")
        }

    var areNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()
            Timber.d("Notifications enabled: $value")
        }

    var mapStyle: String
        get() = prefs.getString(KEY_MAP_STYLE, "standard") ?: "standard"
        set(value) {
            prefs.edit().putString(KEY_MAP_STYLE, value).apply()
            Timber.d("Map style set to: $value")
        }

    var isDrawTrackEnabled: Boolean
        get() = prefs.getBoolean(KEY_DRAW_TRACK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DRAW_TRACK, value).apply()
            Timber.d("Draw track enabled: $value")
        }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()
        }

    var isTrackingActive: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ACTIVE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_ACTIVE, value).apply()
            Timber.d("Tracking active: $value")
        }

    fun saveLastMapPosition(latitude: Double, longitude: Double, zoom: Float) {
        prefs.edit().apply {
            putFloat(KEY_LAST_LATITUDE, latitude.toFloat())
            putFloat(KEY_LAST_LONGITUDE, longitude.toFloat())
            putFloat(KEY_LAST_ZOOM, zoom)
            apply()
        }
    }

    fun getLastMapPosition(): Triple<Double, Double, Float>? {
        if (!prefs.contains(KEY_LAST_LATITUDE)) return null

        val lat = prefs.getFloat(KEY_LAST_LATITUDE, 0f).toDouble()
        val lon = prefs.getFloat(KEY_LAST_LONGITUDE, 0f).toDouble()
        val zoom = prefs.getFloat(KEY_LAST_ZOOM, Constants.DEFAULT_MAP_ZOOM.toFloat())

        return Triple(lat, lon, zoom)
    }

    fun clear() {
        prefs.edit().clear().apply()
        Timber.d("Preferences cleared")
    }

    private fun applyDarkMode(enabled: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }
}