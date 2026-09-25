package com.surveyme.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.surveyme.R
import com.surveyme.core.Constants
import com.surveyme.core.LocationCadence
import com.surveyme.core.PreferencesManager
import com.surveyme.core.ProximityDetector
import com.surveyme.core.QuietHours
import com.surveyme.data.NearbyFetcher
import com.surveyme.data.PoiManager
import com.surveyme.data.model.Poi
import com.surveyme.data.TrackManager
import com.surveyme.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import com.surveyme.data.model.ActiveTrack
import com.surveyme.data.model.TrackPoint

class LocationTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "location_tracking_channel"
        const val NOTIFICATION_ID = 1001
        const val POI_NOTIFICATION_CHANNEL_ID = "poi_proximity_channel"
        const val ACTION_START_TRACKING = "com.surveyme.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.surveyme.STOP_TRACKING"
        const val ACTION_MUTE_POI = "com.surveyme.MUTE_POI"
        const val EXTRA_POI_ID = "com.surveyme.POI_ID"
        private const val POI_NOTIFICATION_ID_OFFSET = 2000

        /** The ongoing notification is refreshed at most this often (a tier change refreshes it at once). */
        private const val NOTIFICATION_REFRESH_MILLIS = 30_000L
        /** How often the service asks the tier selector whether the fixes have gone quiet. */
        private const val SILENCE_TICK_MILLIS = 30_000L
        /** Battery summary line in the log. */
        private const val SUMMARY_INTERVAL_MILLIS = 10 * 60_000L
        /** A fetch holds the CPU at most this long; the fetcher's own timeouts are shorter. */
        private const val FETCH_WAKE_LOCK_MILLIS = 45_000L
        /** A re-request often echoes the cached last fix; older than this it is not where the walker is. */
        private const val STALE_FIX_MILLIS = 30_000L

        private val _isTrackingFlow = MutableStateFlow(false)
        val isTrackingFlow: StateFlow<Boolean> = _isTrackingFlow.asStateFlow()

        private val _activeTrackFlow = MutableStateFlow(ActiveTrack())
        val activeTrackFlow: StateFlow<ActiveTrack> = _activeTrackFlow.asStateFlow()

        /** Last fix while tracking, null otherwise. Lets the UI fetch around the walker, not the map. */
        private val _lastLocationFlow = MutableStateFlow<Location?>(null)
        val lastLocationFlow: StateFlow<Location?> = _lastLocationFlow.asStateFlow()

        /** Counters of the current (or last) tracking session, for Settings and the log. */
        private val _statsFlow = MutableStateFlow(TrackingStats())
        val statsFlow: StateFlow<TrackingStats> = _statsFlow.asStateFlow()
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var notificationManager: NotificationManager
    private lateinit var proximityDetector: ProximityDetector
    private lateinit var fetcher: NearbyFetcher
    private lateinit var prefs: PreferencesManager
    private lateinit var fetchWakeLock: PowerManager.WakeLock

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val poisCache = mutableListOf<Poi>()
    private var isTracking = false

    // Battery pass (item 14). All of these are touched on the main looper only.
    private var tierSelector = LocationCadence.TierSelector()
    private var requestedTier: LocationCadence.Tier? = null
    private var lastCheckedLocation: Location? = null
    private var lastCheckedAt = 0L
    private var lastNotificationAt = 0L
    private var lastFetchLaunchAt = 0L
    private var fetchJob: Job? = null
    private var silenceJob: Job? = null
    private var summaryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("LocationTrackingService onCreate")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        proximityDetector = ProximityDetector()
        fetcher = PoiManager.getFetcher(this)
        prefs = PreferencesManager(this)
        fetchWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "surveyme:fetch")
            .apply { setReferenceCounted(false) }

        createNotificationChannels()
        setupLocationCallback()
        loadPois()
    }

    private fun createNotificationChannels() {
        // Main tracking channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val trackingChannel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when the app is tracking your location"
                setShowBadge(false)
            }

            // POI proximity channel
            val poiChannel = NotificationChannel(
                POI_NOTIFICATION_CHANNEL_ID,
                "POI Proximity Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when you're near a point of interest"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
            }

            notificationManager.createNotificationChannel(trackingChannel)
            notificationManager.createNotificationChannel(poiChannel)
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }
    }

    private fun handleLocationUpdate(location: Location) {
        val now = System.currentTimeMillis()
        val age = now - location.time
        if (age > STALE_FIX_MILLIS) {
            // Neither the cadence (its silence clock would read it as minutes old), the proximity
            // pass nor the track should see a fix from before the request was made.
            Timber.d("Stale fix dropped (${age / 1000}s old)")
            return
        }
        Timber.d("Location update: ${location.latitude}, ${location.longitude}")
        _lastLocationFlow.value = location
        _statsFlow.update { it.copy(fixes = it.fixes + 1, updatedAtMillis = now) }

        // Cadence first, so a walker setting out gets the faster request before anything else.
        val fix = LocationCadence.Fix(
            location.latitude, location.longitude, location.time,
            if (location.hasSpeed()) location.speed else null,
            if (location.hasAccuracy()) location.accuracy else null
        )
        tierSelector.onFix(fix)?.let { tier ->
            applyTier(tier, "speed %.1f m/s".format(tierSelector.smoothedMps ?: fix.speedMps ?: 0f))
        }

        checkProximity(location, now)

        // --- Track Recording Logic ---
        val currentTrack = _activeTrackFlow.value
        val lastPoint = currentTrack.points.lastOrNull()

        var incrementalDistance = 0f
        if (lastPoint != null) {
            val results = FloatArray(1)
            Location.distanceBetween(
                lastPoint.latitude, lastPoint.longitude,
                location.latitude, location.longitude,
                results
            )
            incrementalDistance = results[0]
        }

        val totalDistance = currentTrack.totalDistance + incrementalDistance
        val startTime = if (currentTrack.startTime == 0L) System.currentTimeMillis() else currentTrack.startTime
        val duration = System.currentTimeMillis() - startTime

        val newPoint = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            time = location.time,
            distanceFromStart = totalDistance
        )

        _activeTrackFlow.value = currentTrack.copy(
            points = currentTrack.points + newPoint,
            totalDistance = totalDistance,
            startTime = startTime,
            duration = duration
        )
        // ------------------------------

        // The ongoing notification is one IPC per refresh; a fix every 5 s does not need one each.
        if (now - lastNotificationAt >= NOTIFICATION_REFRESH_MILLIS) updateNotification(location)

        refetchIfMoved(location, now)
    }

    /**
     * The proximity pass walks every pingable POI. It is skipped when the fix is within a few
     * metres of the last one checked (unless a minute passed, so cooldowns and fresh fetches
     * are seen), when the fix is too coarse to trust, in quiet hours, and with pings off.
     */
    private fun checkProximity(location: Location, now: Long) {
        val moved = lastCheckedLocation?.distanceTo(location)
        val since = if (lastCheckedLocation == null) null else now - lastCheckedAt
        if (!LocationCadence.shouldCheckProximity(moved, since)) {
            Timber.v("Proximity pass skipped: moved ${moved?.toInt()}m in ${since?.div(1000)}s")
            return
        }
        val pingRadius = prefs.pingRadiusMeters
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        if (!LocationCadence.isFixUsableForPings(accuracy, pingRadius)) {
            Timber.d("Proximity pass skipped: fix accuracy ${accuracy?.toInt()}m, ping radius ${pingRadius}m")
            return
        }
        lastCheckedLocation = location
        lastCheckedAt = now
        _statsFlow.update { it.copy(proximityPasses = it.proximityPasses + 1) }

        // Item 13: quiet hours suppress pings, not tracking or fetching. The detector is not
        // called at all, so nothing is marked notified and the items ping once the window ends.
        // The Notifications switch in Settings is the same kind of gate.
        val quiet = QuietHours.isQuiet(prefs.quietHours)
        if (quiet || !prefs.areNotificationsEnabled) {
            Timber.d("Pings suppressed (${if (quiet) "quiet hours" else "notifications off"})")
            return
        }
        // Create a snapshot of the POIs to avoid ConcurrentModificationException
        // if the list is updated on a background thread while iterating
        val currentPois = synchronized(poisCache) {
            poisCache.filter { !it.isMuted }
        }
        if (currentPois.isEmpty()) {
            Timber.d("No POIs in cache for proximity detection")
            return
        }
        proximityDetector.checkProximity(
            location.latitude,
            location.longitude,
            currentPois,
            pingRadius
        ) { poi, distance ->
            Timber.i("Ping: ${poi.name} at ${distance.toInt()}m")
            _statsFlow.update { it.copy(pings = it.pings + 1) }
            showPoiNotification(poi, distance)
            markPoiAsVisited(poi.id)
        }
    }

    /**
     * Fetch follows the walker: when we are more than half the fetch radius from the last fetch
     * centre (or the last fetch is stale) refetch notes and fixmes around here. The fetcher owns
     * the rate limits and never runs two fetches at once; notes and fixmes go in one call, so one
     * coroutine and one wake window cover both. This launches at most once a minute and never
     * while a fetch is running; the pre-check reads prefs only, no database. The POI cache
     * updates by itself through the Room flow in loadPois().
     */
    private fun refetchIfMoved(location: Location, now: Long) {
        if (!isTracking) return
        if (fetchJob?.isActive == true) return
        if (now - lastFetchLaunchAt < Constants.FETCH_MIN_INTERVAL_MILLIS) return
        if (!fetcher.wantsRefetch(location.latitude, location.longitude, now)) return
        lastFetchLaunchAt = now
        _statsFlow.update { it.copy(fetches = it.fetches + 1) }
        // Held only for the fetch: fix handling is sub-millisecond on a looper fused location
        // already woke, but a network round trip must not stall in a CPU nap half way through.
        fetchWakeLock.acquire(FETCH_WAKE_LOCK_MILLIS)
        fetchJob = serviceScope.launch {
            try {
                fetcher.fetch(location.latitude, location.longitude, NearbyFetcher.Trigger.WALKER)
            } catch (e: Exception) {
                Timber.e(e, "Walker refetch failed")
            } finally {
                if (fetchWakeLock.isHeld) fetchWakeLock.release()
            }
        }
    }

    /** Requests fused location for [tier]. A request with the same callback replaces the previous one. */
    private fun applyTier(tier: LocationCadence.Tier, why: String) {
        if (tier == requestedTier) return
        val previous = requestedTier
        requestedTier = tier
        lastCheckedLocation = null  // the next fix runs the proximity pass whatever it moved
        _statsFlow.update { it.copy(tier = tier, tierChanges = if (previous == null) it.tierChanges else it.tierChanges + 1) }
        Timber.i(
            "Cadence: ${previous?.name?.lowercase() ?: "start"} -> ${tier.name.lowercase()} ($why): " +
                "every ${tier.intervalMillis / 1000}s, ${if (tier.highAccuracy) "high accuracy" else "balanced power"}, " +
                "min ${tier.minDisplacementMeters.toInt()}m"
        )
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Cadence: no location permission, not requesting updates")
            return
        }
        val priority = if (tier.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, tier.intervalMillis)
            .setMinUpdateIntervalMillis(tier.fastestIntervalMillis)
            .setMinUpdateDistanceMeters(tier.minDisplacementMeters)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        _lastLocationFlow.value?.let { updateNotification(it) }
    }

    private fun batteryPercent(): Int? {
        val manager = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return null
        // Some OEMs answer Integer.MIN_VALUE instead of a percentage.
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }

    private fun logSummary() {
        _statsFlow.update { it.copy(batteryNow = batteryPercent(), updatedAtMillis = System.currentTimeMillis()) }
        Timber.i("Battery: ${_statsFlow.value.describe()}")
    }

    private fun loadPois() {
        serviceScope.launch {
            try {
                val repository = PoiManager.getRepository(this@LocationTrackingService)
                // Closed notes stay in the store for a while but must not ping.
                repository.getPingablePois().collectLatest { pois ->
                    synchronized(poisCache) {
                        poisCache.clear()
                        poisCache.addAll(pois)
                    }
                    Timber.d("Loaded ${pois.size} POIs for proximity detection")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load POIs")
            }
        }
    }

    private fun markPoiAsVisited(poiId: String) {
        serviceScope.launch {
            try {
                val repository = PoiManager.getRepository(this@LocationTrackingService)
                repository.markAsVisited(poiId)
                repository.updateLastNotificationTime(poiId)
                proximityDetector.markPoiAsVisited(poiId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to mark POI as visited")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> startLocationTracking()
            ACTION_STOP_TRACKING -> stopLocationTracking()
            ACTION_MUTE_POI -> intent.getStringExtra(EXTRA_POI_ID)?.let { mutePoi(it) }
        }
        return START_STICKY
    }

    private fun startLocationTracking() {
        if (isTracking) return

        Timber.d("Starting location tracking")
        isTracking = true
        _isTrackingFlow.value = true
        _activeTrackFlow.value = ActiveTrack() // Reset track

        val now = System.currentTimeMillis()
        val battery = batteryPercent()
        _statsFlow.value = TrackingStats(
            active = true, startedAtMillis = now, batteryAtStart = battery, batteryNow = battery, updatedAtMillis = now
        )
        tierSelector = LocationCadence.TierSelector()
        requestedTier = null
        lastCheckedLocation = null
        lastFetchLaunchAt = 0L
        lastNotificationAt = 0L

        // Start as foreground service
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Request location updates at the walking cadence; the fixes decide from there.
        applyTier(tierSelector.tier, "tracking started")

        silenceJob = serviceScope.launch {
            while (isActive) {
                delay(SILENCE_TICK_MILLIS)
                val tier = tierSelector.onSilence(System.currentTimeMillis()) ?: continue
                withContext(Dispatchers.Main) { applyTier(tier, "no fix for ${LocationCadence.Tier.STATIONARY.holdBeforeMillis / 60_000} min") }
            }
        }
        summaryJob = serviceScope.launch {
            while (isActive) {
                delay(SUMMARY_INTERVAL_MILLIS)
                logSummary()
            }
        }
    }

    private fun stopLocationTracking() {
        if (!isTracking) return

        Timber.d("Stopping location tracking")
        isTracking = false
        _isTrackingFlow.value = false
        _lastLocationFlow.value = null
        silenceJob?.cancel(); silenceJob = null
        summaryJob?.cancel(); summaryJob = null
        logSummary()
        _statsFlow.update { it.copy(active = false) }

        // Save track before resetting if it has points
        val currentTrack = _activeTrackFlow.value
        if (currentTrack.points.isNotEmpty()) {
            serviceScope.launch {
                try {
                    val trackRepository = TrackManager.getRepository(this@LocationTrackingService)
                    // Basic date formatting for default name
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    val dateString = dateFormat.format(java.util.Date(currentTrack.startTime))
                    val defaultName = "Track - $dateString"

                    trackRepository.saveActiveTrack(currentTrack, defaultName)
                    Timber.d("Successfully saved track: $defaultName with ${currentTrack.points.size} points")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to save track to database")
                } finally {
                    // Reset track after attempting save
                    _activeTrackFlow.value = ActiveTrack()
                }
            }
        } else {
            _activeTrackFlow.value = ActiveTrack()
        }

        fusedLocationClient.removeLocationUpdates(locationCallback)
        requestedTier = null
        stopForeground(true)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationTrackingService::class.java).apply {
                action = ACTION_STOP_TRACKING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Survey Me - Tracking")
            .setContentText("Recording your survey route")
            .setSmallIcon(R.drawable.ic_home)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_home, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(location: Location) {
        lastNotificationAt = System.currentTimeMillis()
        val tier = (requestedTier ?: tierSelector.tier).name.lowercase()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Survey Me - Tracking")
            .setContentText("$tier · ${location.latitude.format(5)}, ${location.longitude.format(5)}")
            .setSmallIcon(R.drawable.ic_home)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun mutePoi(poiId: String) {
        Timber.d("Muting POI $poiId from notification action")
        notificationManager.cancel(poiNotificationId(poiId))
        serviceScope.launch {
            try {
                PoiManager.getRepository(this@LocationTrackingService).setMuted(poiId, true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to mute POI $poiId")
            } finally {
                // Mute tapped after tracking stopped revived the service; do not linger.
                if (!isTracking) stopSelf()
            }
        }
    }

    private fun poiNotificationId(poiId: String) = poiId.hashCode() + POI_NOTIFICATION_ID_OFFSET

    private fun showPoiNotification(poi: Poi, distance: Float) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("surveyme://poi/${poi.id}") // makes each PendingIntent distinct
            putExtra(MainActivity.EXTRA_OPEN_POI_ID, poi.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            poi.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_MUTE_POI
            data = Uri.parse("surveyme://mute/${poi.id}")
            putExtra(EXTRA_POI_ID, poi.id)
        }
        val mutePending = PendingIntent.getService(
            this,
            poi.id.hashCode() + 1,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (poi.source) {
            "osm_notes" -> "Note nearby"
            "osm_fixme" -> "Fixme nearby"
            else -> "Nearby: ${poi.name}"
        }
        // description holds the note's first comment (or the fixme text); notes holds the
        // full thread with "[user at date]:" headers, which is only useful expanded.
        val summary = (poi.description ?: poi.name).trim()
        val firstLine = summary.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: poi.name
        val body = (poi.notes ?: summary).trim()
        val meters = distance.toInt()

        val notification = NotificationCompat.Builder(this, POI_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("$title, ${meters}m")
            .setContentText(firstLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${meters}m away\n$body"))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(openPending)
            .addAction(0, "Mute this one", mutePending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()

        notificationManager.notify(poiNotificationId(poi.id), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("LocationTrackingService onDestroy")
        serviceScope.cancel()
        if (fetchWakeLock.isHeld) fetchWakeLock.release()
        if (isTracking) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
