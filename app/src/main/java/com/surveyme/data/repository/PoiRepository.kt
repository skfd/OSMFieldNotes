package com.surveyme.data.repository

import android.content.Context
import com.surveyme.data.database.SurveyMeDatabase
import com.surveyme.data.geojson.GeoJsonParser
import com.surveyme.data.gpx.GpxParser
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
    private val gpxParser = GpxParser()
    private val geoJsonParser = GeoJsonParser()

    fun getAllActivePois(): Flow<List<Poi>> = poiDao.getAllActivePois()

    fun getPoisByCategory(category: PoiCategory): Flow<List<Poi>> =
        poiDao.getPoisByCategory(category)

    fun getNotesAndFixmes(): Flow<List<Poi>> = poiDao.getNotesAndFixmes()

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

    suspend fun deleteAllFromSource(source: String) = poiDao.deleteAllFromSource(source)

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
        stale.chunked(500).forEach { poiDao.deleteByIds(it) }
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