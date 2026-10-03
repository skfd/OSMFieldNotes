package com.surveyme.data

import android.location.Location
import com.surveyme.core.Constants
import com.surveyme.core.FetchCity
import com.surveyme.core.PreferencesManager
import com.surveyme.data.model.Poi
import com.surveyme.data.repository.PoiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * The one place that fetches open notes (osm.org) and fixmes (Overpass) around a point and
 * stores them. Map load, the Settings button and the tracking service all come through here.
 *
 * Each source has its own gate: a persisted last fetch centre (used for the "moved more than
 * half the radius" and staleness checks), a minimum interval between fetches, and an
 * exponential backoff after HTTP or network failures. Two fetches never overlap; a caller
 * that arrives while one is running is told [Outcome.Busy] and does nothing.
 *
 * After a successful fetch of a source, rows of that source farther than the radius from the
 * new centre are deleted and the fresh rows are merged through [PoiRepository.insertPois],
 * which keeps muted, visited and last-ping flags on rows that already existed. Notes also go
 * through [PoiRepository.storeFetchedNotes], which drops in-radius notes the API stopped
 * returning unless they were closed recently.
 *
 * With a [PreferencesManager.fetchCity] set, the area is that city instead of a circle: the
 * caller's point is ignored, movement never triggers a refetch, only an hour's staleness or a
 * different city does, and expiry keeps everything inside the city's box.
 */
class NearbyFetcher(
    private val repository: PoiRepository,
    private val prefs: PreferencesManager,
    private val notesService: OsmNotesService = OsmNotesService(),
    private val overpassService: OverpassService = OverpassService(),
    /** The clock every gate reads; injectable so the unit tests can walk time forward. */
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class Trigger {
        /** Location update from the tracking service. Movement or staleness check, gate applies. */
        WALKER,
        /** Map tab opened. Same rules as [WALKER], around the last map position. */
        MAP_LOAD,
        /** Settings button. Skips the movement check and the minimum interval, still honours backoff. */
        MANUAL
    }

    sealed class Outcome {
        data class Fetched(val fetched: Int, val expired: Int) : Outcome()
        data class Skipped(val reason: String) : Outcome()
        data class Failed(val message: String, val retryInMillis: Long) : Outcome()
        object Busy : Outcome()

        fun describe(): String = when (this) {
            is Fetched -> "$fetched found, $expired expired"
            is Skipped -> "skipped ($reason)"
            is Failed -> "$message, retry in ${retryInMillis / 1000}s"
            Busy -> "busy"
        }
    }

    data class Report(val notes: Outcome, val fixmes: Outcome) {
        val touchedDatabase: Boolean get() = notes is Outcome.Fetched || fixmes is Outcome.Fetched
        fun describe(): String = "notes: ${notes.describe()}; fixmes: ${fixmes.describe()}"
    }

    /** Per-source rate limit state. In memory only; the process restarts with a clean slate. */
    private class Gate(val source: String) {
        var nextAllowedAt = 0L
        var backoffMillis = 0L
    }

    private val notesGate = Gate(OsmNotesService.SOURCE_NOTES)
    private val fixmesGate = Gate(OverpassService.SOURCE_FIXME)
    private val running = Mutex()
    private var legacyPurged = false

    /**
     * What one fetch covers. [lat], [lon] and [radiusMeters] are what the fetch centre is saved
     * with ([radiusMeters] is 0 for a city); [contains] is the expiry and the in-range filter.
     */
    private class Area(
        val key: String,
        val lat: Double,
        val lon: Double,
        val radiusMeters: Int,
        val city: FetchCity?,
        val contains: (Poi) -> Boolean
    ) {
        override fun toString() = city?.let { "${it.name} ($key)" } ?: "$lat,$lon r=${radiusMeters}m"
    }

    private fun areaFor(lat: Double, lon: Double, radiusMeters: Int): Area {
        val city = prefs.fetchCity
        return if (city != null) {
            Area(city.areaKey, city.centreLat, city.centreLon, 0, city) { city.contains(it.latitude, it.longitude) }
        } else {
            Area(FetchCity.radiusAreaKey(radiusMeters), lat, lon, radiusMeters, null) { it.distanceTo(lat, lon) <= radiusMeters }
        }
    }

    /**
     * Cheap pre-check for the tracking service so it does not launch a coroutine on every
     * location update. True when either source would want a refetch here, before rate limits.
     */
    fun wantsRefetch(lat: Double, lon: Double, now: Long = clock()): Boolean {
        if (prefs.fetchCity == null && isNoPosition(lat, lon)) return false
        // A source counts only when its gate is open (no min interval or backoff pending) and it
        // has moved or gone stale; otherwise a backed-off source would launch a no-op every tick.
        return listOf(notesGate, fixmesGate).any { gate ->
            now >= gate.nextAllowedAt && movedOrStale(gate.source, lat, lon, now) != null
        }
    }

    /**
     * Fetch both sources around ([lat], [lon]) if their gates allow, or the whole city when
     * one is set (the point is then ignored). Never throws. The radius is the item 13 setting
     * unless the caller says otherwise; the expiry uses the same area.
     */
    suspend fun fetch(
        lat: Double,
        lon: Double,
        trigger: Trigger,
        radiusMeters: Int = prefs.fetchRadiusMeters
    ): Report {
        val area = areaFor(lat, lon, radiusMeters)
        if (area.city == null && isNoPosition(lat, lon)) {
            // The map's default centre before any position was ever saved; fetching there would
            // store 0,0 as the centre and expire everything real.
            Timber.d("Fetch ($trigger) skipped: no position yet")
            return Report(Outcome.Skipped("no position"), Outcome.Skipped("no position"))
        }
        if (!running.tryLock()) {
            Timber.d("Fetch ($trigger) skipped: another fetch is running")
            return Report(Outcome.Busy, Outcome.Busy)
        }
        try {
            if (!legacyPurged) {
                // Rows left by earlier builds under the old source tag; harmless once gone.
                repository.deleteAllFromSource(OverpassService.SOURCE_LEGACY)
                legacyPurged = true
            }

            val city = area.city
            val notes = fetchSource(
                notesGate, area, trigger,
                call = {
                    if (city != null) notesService.fetchNotesInBox(city.west, city.south, city.east, city.north)
                    else notesService.fetchNotes(lat, lon, radiusMeters)
                },
                items = { page -> page.notes },
                poiOf = { it.poi },
                store = { page, inRange ->
                    repository.storeFetchedNotes(inRange, area.contains, page.truncated)
                }
            )
            val fixmes = fetchSource(
                fixmesGate, area, trigger,
                call = {
                    withContext(Dispatchers.IO) {
                        if (city != null) overpassService.fetchFixmePoisInRelation(city.relationId)
                        else overpassService.fetchFixmePois(lat, lon, radiusMeters)
                    }
                },
                items = { it },
                poiOf = { it },
                store = { _, inRange ->
                    val expired = repository.expireOutside(fixmesGate.source, area.contains)
                    repository.insertPois(inRange)
                    expired
                }
            )
            return Report(notes, fixmes).also { Timber.i("Fetch ($trigger) for $area: ${it.describe()}") }
        } finally {
            running.unlock()
        }
    }

    /**
     * One gated fetch of a source. [call] hits the network and answers a page [P]; [items] and
     * [poiOf] let the area filter see each item's POI; [store] writes the in-range items and
     * returns how many stored rows it expired or dropped.
     */
    private suspend fun <P, T> fetchSource(
        gate: Gate,
        area: Area,
        trigger: Trigger,
        call: suspend () -> P,
        items: (P) -> List<T>,
        poiOf: (T) -> Poi,
        store: suspend (P, List<T>) -> Int
    ): Outcome {
        val now = clock()

        val why = if (trigger == Trigger.MANUAL) "manual" else movedOrStale(gate.source, area.lat, area.lon, now)
        if (why == null) {
            val reason = if (area.city != null) "city fetched under an hour ago" else "not moved far enough and not stale"
            return Outcome.Skipped(reason).also {
                Timber.d("${gate.source}: ${it.reason}")
            }
        }
        if (now < gate.nextAllowedAt) {
            val wait = gate.nextAllowedAt - now
            val reason = if (gate.backoffMillis > 0) "backing off ${wait / 1000}s" else "min interval, ${wait / 1000}s left"
            // A manual tap may jump the minimum interval but never a backoff after a failure.
            if (trigger != Trigger.MANUAL || gate.backoffMillis > 0) {
                return Outcome.Skipped(reason).also { Timber.d("${gate.source}: $reason") }
            }
        }

        Timber.d("${gate.source}: fetching ($why) for $area")
        val fresh = try {
            call()
        } catch (e: FetchHttpException) {
            return fail(gate, now, e.message ?: "HTTP ${e.code}", e.retryAfterSeconds?.times(1000))
        } catch (e: IOException) {
            return fail(gate, now, e.message ?: "network error", null)
        } catch (e: Exception) {
            Timber.e(e, "${gate.source}: unexpected failure")
            return fail(gate, now, e.message ?: e.javaClass.simpleName, null)
        }

        // The notes API answers a bounding square; keep the stored set a circle so items in the
        // corners do not get inserted and then expired on every fetch.
        val inRange = items(fresh).filter { area.contains(poiOf(it)) }
        val expired = store(fresh, inRange)

        gate.backoffMillis = 0L
        gate.nextAllowedAt = now + Constants.FETCH_MIN_INTERVAL_MILLIS
        prefs.saveFetchCentre(gate.source, PreferencesManager.FetchCentre(area.lat, area.lon, area.radiusMeters, now, area.key))
        return Outcome.Fetched(inRange.size, expired)
    }

    private fun fail(gate: Gate, now: Long, message: String, retryAfterMillis: Long?): Outcome {
        val doubled = if (gate.backoffMillis == 0L) Constants.FETCH_BACKOFF_BASE_MILLIS else gate.backoffMillis * 2
        gate.backoffMillis = maxOf(doubled, retryAfterMillis ?: 0L).coerceAtMost(Constants.FETCH_BACKOFF_MAX_MILLIS)
        gate.nextAllowedAt = now + gate.backoffMillis
        Timber.w("${gate.source}: fetch failed ($message), next try in ${gate.backoffMillis / 1000}s")
        return Outcome.Failed(message, gate.backoffMillis)
    }

    private fun isNoPosition(lat: Double, lon: Double) =
        lat == Constants.DEFAULT_MAP_CENTER_LAT && lon == Constants.DEFAULT_MAP_CENTER_LON

    /**
     * Why this source wants a refetch at the point, or null if the last fetch still covers it.
     * The movement threshold is half the radius the last fetch was made with; a changed radius
     * setting is its own reason, or growing 2 km to 5 km would do nothing until the walker had
     * moved 1 km or 15 min had passed. Switching to or from a city, or between cities, is a
     * reason too. A city never moves, so only its own longer staleness refetches it.
     */
    private fun movedOrStale(source: String, lat: Double, lon: Double, now: Long): String? {
        val last = prefs.getFetchCentre(source) ?: return "never fetched"
        val wanted = prefs.fetchAreaKey
        if (last.areaKey != wanted) return "area changed ${last.areaKey} to $wanted"
        if (prefs.fetchCity != null) {
            val age = now - last.timeMillis
            return if (age > Constants.CITY_FETCH_STALE_MILLIS) "city stale, ${age / 60000}min old" else null
        }
        val results = FloatArray(1)
        Location.distanceBetween(last.latitude, last.longitude, lat, lon, results)
        val moved = results[0].toInt()
        val threshold = last.radiusMeters / 2
        if (moved > threshold) return "moved ${moved}m of ${threshold}m"
        val age = now - last.timeMillis
        if (age > Constants.FETCH_STALE_MILLIS) return "stale, ${age / 60000}min old"
        return null
    }
}
