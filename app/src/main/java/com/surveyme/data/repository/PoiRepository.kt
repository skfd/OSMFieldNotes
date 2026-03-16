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

    fun getUnvisitedPois(): Flow<List<Poi>> = poiDao.getUnvisitedPois()

    suspend fun getPoiById(id: String): Poi? = poiDao.getPoiById(id)

    suspend fun getPoisInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<Poi> = poiDao.getPoisInBounds(minLat, maxLat, minLon, maxLon)

    suspend fun insertPoi(poi: Poi) = poiDao.insertPoi(poi)

    suspend fun insertPois(pois: List<Poi>) = poiDao.insertPois(pois)

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