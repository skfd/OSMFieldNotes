package com.surveyme.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.surveyme.core.Constants
import com.surveyme.data.FetchedNote
import com.surveyme.data.OsmNotesService
import com.surveyme.data.database.PoiWithNote
import com.surveyme.data.database.SurveyMeDatabase
import com.surveyme.data.geojson.GeoJsonParser
import com.surveyme.data.gpx.GpxParser
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.data.model.PoiCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.InputStream
class PoiRepository(
    private val database: SurveyMeDatabase,
    private val context: Context
) {
    private val poiDao = database.poiDao()
    private val noteDao = database.noteDao()
    private val gpxParser = GpxParser()
    private val geoJsonParser = GeoJsonParser()

    fun getAllActivePois(): Flow<List<Poi>> = poiDao.getAllActivePois()

    fun getPoisByCategory(category: PoiCategory): Flow<List<Poi>> =
        poiDao.getPoisByCategory(category)

    fun getNotesAndFixmes(): Flow<List<Poi>> = poiDao.getNotesAndFixmes()

    /** Notes tab rows: each POI with its structured note when it has one. */
    fun getNotesAndFixmesWithNote(): Flow<List<PoiWithNote>> = poiDao.getNotesAndFixmesWithNote()

    /** What proximity may ping: active POIs minus closed notes. */
    fun getPingablePois(): Flow<List<Poi>> = poiDao.getPingablePois()

    suspend fun getNoteByPoiId(poiId: String): OsmNote? = noteDao.getByPoiId(poiId)

    fun observeNoteByPoiId(poiId: String): Flow<OsmNote?> = noteDao.observeByPoiId(poiId)

    fun getUnvisitedPois(): Flow<List<Poi>> = poiDao.getUnvisitedPois()

    suspend fun getPoiById(id: String): Poi? = poiDao.getPoiById(id)

    suspend fun getPoisInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<Poi> = poiDao.getPoisInBounds(minLat, maxLat, minLon, maxLon)

    suspend fun insertPoi(poi: Poi) = poiDao.insertPoi(poi)

    /**
     * Insert or replace, keeping the per-user flags (muted, visited, last ping) of rows
     * that already exist. Refetching notes must not un-mute a note.
     */
    suspend fun insertPois(pois: List<Poi>) {
        if (pois.isEmpty()) return
        val existing = pois.map { it.id }
            .chunked(500)
            .flatMap { poiDao.getPoisByIds(it) }
            .associateBy { it.id }
        val merged = pois.map { poi ->
            val old = existing[poi.id] ?: return@map poi
            poi.copy(
                isMuted = old.isMuted,
                isVisited = old.isVisited,
                lastNotificationTime = old.lastNotificationTime
            )
        }
        poiDao.insertPois(merged)
    }

    /**
     * Insert or replace note rows, keeping the app's own fields ([OsmNote.labels],
     * [OsmNote.surveyAfter]) of rows that already exist. The API never knows about those.
     */
    suspend fun insertNotes(notes: List<OsmNote>) {
        if (notes.isEmpty()) return
        val existing = notes.map { it.noteId }
            .chunked(500)
            .flatMap { noteDao.getByNoteIds(it) }
            .associateBy { it.noteId }
        val merged = notes.map { note ->
            val old = existing[note.noteId] ?: return@map note
            note.copy(labels = old.labels, surveyAfter = old.surveyAfter)
        }
        noteDao.insertAll(merged)
    }

    /**
     * Store one page of notes fetched around ([lat], [lon]). In one transaction: expire note
     * rows outside the radius, drop in-radius notes the API no longer returns (it returns open
     * notes and ones closed within 7 days, so an absent note is closed or hidden) unless they
     * were closed within [Constants.NOTE_CLOSED_RETENTION_MILLIS], then merge the page in,
     * keeping ping flags on the POI and labels/surveyAfter on the note. When [truncated] the
     * page is not the whole area and nothing in radius is dropped. Returns how many rows went.
     */
    suspend fun storeFetchedNotes(
        page: List<FetchedNote>,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        truncated: Boolean,
        now: Long = System.currentTimeMillis()
    ): Int = database.withTransaction {
        val source = OsmNotesService.SOURCE_NOTES
        val expiredOutside = expireOutsideRadius(source, lat, lon, radiusMeters)

        var dropped = 0
        if (!truncated) {
            val returned = page.map { it.poi.id }.toSet()
            val absent = poiDao.getPoisBySource(source).filter { it.id !in returned }
            if (absent.isNotEmpty()) {
                val notesByPoi = absent.map { it.id }
                    .chunked(500)
                    .flatMap { noteDao.getByPoiIds(it) }
                    .associateBy { it.poiId }
                val toDrop = absent.filter { poi ->
                    val note = notesByPoi[poi.id]
                    val closedRecently = note != null && !note.isOpen &&
                        (note.closedAt?.time ?: 0L) > now - Constants.NOTE_CLOSED_RETENTION_MILLIS
                    !closedRecently
                }.map { it.id }
                deletePoisAndNotes(toDrop)
                dropped = toDrop.size
                if (dropped > 0) Timber.d("Dropped $dropped notes the API no longer returns")
            }
        }

        insertPois(page.map { it.poi })
        insertNotes(page.map { it.note })
        expiredOutside + dropped
    }

    suspend fun setMuted(poiId: String, muted: Boolean) {
        poiDao.setMuted(poiId, muted)
        Timber.d("POI muted=$muted: $poiId")
    }

    suspend fun updatePoi(poi: Poi) = poiDao.updatePoi(poi)

    suspend fun markAsVisited(poiId: String) {
        poiDao.markAsVisited(poiId)
        Timber.d("POI marked as visited: $poiId")
    }

    suspend fun updateLastNotificationTime(poiId: String) {
        poiDao.updateLastNotificationTime(poiId, System.currentTimeMillis())
        Timber.d("Updated notification time for POI: $poiId")
    }

    suspend fun deletePoi(poi: Poi) = poiDao.deletePoi(poi)

    suspend fun deleteAllFromSource(source: String) {
        if (source == OsmNotesService.SOURCE_NOTES) noteDao.deleteAll()
        poiDao.deleteAllFromSource(source)
    }

    /** Delete POI rows and, for notes, their note rows. */
    private suspend fun deletePoisAndNotes(ids: List<String>) {
        ids.chunked(500).forEach {
            noteDao.deleteByPoiIds(it)
            poiDao.deleteByIds(it)
        }
    }

    suspend fun getPoisBySource(source: String): List<Poi> = poiDao.getPoisBySource(source)

    /**
     * Delete rows of [source] farther than [radiusMeters] from the centre. Called after a
     * successful refetch so the stored set follows the walker. Muted or visited rows that
     * fall outside the radius go too; their flags only survive while they stay in range.
     * Returns how many were removed.
     */
    suspend fun expireOutsideRadius(source: String, lat: Double, lon: Double, radiusMeters: Int): Int {
        val stale = poiDao.getPoisBySource(source)
            .filter { it.distanceTo(lat, lon) > radiusMeters }
            .map { it.id }
        deletePoisAndNotes(stale)
        if (stale.isNotEmpty()) Timber.d("Expired ${stale.size} $source rows beyond ${radiusMeters}m")
        return stale.size
    }

    suspend fun getStats(): Pair<Int, Int> {
        val active = poiDao.getActivePoiCount()
        val visited = poiDao.getVisitedPoiCount()
        return Pair(active, visited)
    }

    suspend fun importFromGpx(
        inputStream: InputStream,
        sourceName: String = "gpx_import"
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val waypoints = gpxParser.parse(inputStream)
            if (waypoints.isEmpty()) {
                return@withContext Result.failure(Exception("No waypoints found in GPX file"))
            }

            val pois = gpxParser.convertToPois(waypoints, sourceName)
            poiDao.insertPois(pois)

            Timber.d("Imported ${pois.size} POIs from GPX")
            Result.success(pois.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to import GPX")
            Result.failure(e)
        }
    }

}