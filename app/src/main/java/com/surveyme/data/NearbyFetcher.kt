package com.surveyme.data

import android.location.Location
import com.surveyme.core.Constants
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
 */
class NearbyFetcher(
    private val repository: PoiRepository,
    private val prefs: PreferencesManager,
    private val notesService: OsmNotesService = OsmNotesService(),
    private val overpassService: OverpassService = OverpassService()
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
     * Cheap pre-check for the tracking service so it does not launch a coroutine on every
     * location update. True when either source would want a refetch here, before rate limits.
     */
    fun wantsRefetch(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Boolean {
        if (isNoPosition(lat, lon)) return false
        // A source counts only when its gate is open (no min interval or backoff pending) and it
        // has moved or gone stale; otherwise a backed-off source would launch a no-op every tick.
        return listOf(notesGate, fixmesGate).any { gate ->
            now >= gate.nextAllowedAt && movedOrStale(gate.source, lat, lon, now) != null
        }
    }

    /**
     * Fetch both sources around ([lat], [lon]) if their gates allow. Never throws. The radius
     * is the item 13 setting unless the caller says otherwise; the expiry uses the same value.
     */
    suspend fun fetch(
        lat: Double,
        lon: Double,
        trigger: Trigger,
        radiusMeters: Int = prefs.fetchRadiusMeters
    ): Report {
        if (isNoPosition(lat, lon)) {
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

            val notes = fetchSource(
                notesGate, lat, lon, trigger, radiusMeters,
                call = { notesService.fetchNotes(lat, lon, radiusMeters) },
                items = { page -> page.notes },
                poiOf = { it.poi },
                store = { page, inRange ->
                    repository.storeFetchedNotes(inRange, lat, lon, radiusMeters, page.truncated)
                }
            )
            val fixmes = fetchSource(
                fixmesGate, lat, lon, trigger, radiusMeters,
                call = { withContext(Dispatchers.IO) { overpassService.fetchFixmePois(lat, lon, radiusMeters) } },
                items = { it },
                poiOf = { it },
                store = { _, inRange ->
                    val expired = repository.expireOutsideRadius(fixmesGate.source, lat, lon, radiusMeters)
                    repository.insertPois(inRange)
                    expired
                }
            )
            return Report(notes, fixmes).also { Timber.i("Fetch ($trigger) at $lat,$lon: ${it.describe()}") }
        } finally {
            running.unlock()
        }
    }

    /**
     * One gated fetch of a source. [call] hits the network and answers a page [P]; [items] and
     * [poiOf] let the circle filter see each item's POI; [store] writes the in-range items and
     * returns how many stored rows it expired or dropped.
     */
    private suspend fun <P, T> fetchSource(
        gate: Gate,
        lat: Double,
        lon: Double,
        trigger: Trigger,
        radiusMeters: Int,
        call: suspend () -> P,
        items: (P) -> List<T>,
        poiOf: (T) -> Poi,
        store: suspend (P, List<T>) -> Int
    ): Outcome {
        val now = System.currentTimeMillis()

        val why = if (trigger == Trigger.MANUAL) "manual" else movedOrStale(gate.source, lat, lon, now)
        if (why == null) {
            return Outcome.Skipped("not moved far enough and not stale").also {
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

        Timber.d("${gate.source}: fetching ($why) around $lat,$lon r=${radiusMeters}m")
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
        val inRange = items(fresh).filter { poiOf(it).distanceTo(lat, lon) <= radiusMeters }
        val expired = store(fresh, inRange)

        gate.backoffMillis = 0L
        gate.nextAllowedAt = now + Constants.FETCH_MIN_INTERVAL_MILLIS
        prefs.saveFetchCentre(gate.source, PreferencesManager.FetchCentre(lat, lon, radiusMeters, now))
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
     * moved 1 km or 15 min had passed.
     */
    private fun movedOrStale(source: String, lat: Double, lon: Double, now: Long): String? {
        val last = prefs.getFetchCentre(source) ?: return "never fetched"
        val wanted = prefs.fetchRadiusMeters
        if (last.radiusMeters != wanted) return "radius changed ${last.radiusMeters}m to ${wanted}m"
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
