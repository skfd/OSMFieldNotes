package com.surveyme.data

import com.surveyme.data.model.Poi
import com.surveyme.data.model.PoiCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

/** A non-2xx answer from osm.org or Overpass. [retryAfterSeconds] comes from the Retry-After header when present. */
class FetchHttpException(val code: Int, val retryAfterSeconds: Long? = null) :
    java.io.IOException("HTTP $code" + (retryAfterSeconds?.let { ", retry after ${it}s" } ?: ""))

class OsmNotesService {
    companion object {
        const val SOURCE_NOTES = "osm_notes"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    /**
     * Open notes inside the bounding square of [radiusMeters]. Throws [FetchHttpException]
     * on a non-2xx answer and [java.io.IOException] on network trouble so the caller can back off;
     * an empty list means the area really has no open notes.
     */
    suspend fun fetchNotes(
        lat: Double,
        lon: Double,
        radiusMeters: Int = 1000
    ): List<Poi> = withContext(Dispatchers.IO) {
        val bbox = calculateBoundingBox(lat, lon, radiusMeters)
        val limit = 100
        val url = "https://api.openstreetmap.org/api/0.6/notes.json?bbox=${bbox[0]},${bbox[1]},${bbox[2]},${bbox[3]}&limit=$limit"

        Timber.d("Fetching OSM notes from API: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SurveyMe/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.e("OSM Notes API request failed: ${response.code}")
                throw FetchHttpException(response.code, response.header("Retry-After")?.trim()?.toLongOrNull())
            }
            val responseBody = response.body?.string()
                ?: throw java.io.IOException("OSM Notes API response body is empty")
            parseNotesJson(responseBody)
        }
    }

    private fun calculateBoundingBox(lat: Double, lon: Double, radiusMeters: Int): DoubleArray {
        // Approximate calculation
        val latOffset = radiusMeters / 111320.0
        val lonOffset = radiusMeters / (40075000.0 * Math.cos(Math.toRadians(lat)) / 360.0)
        
        val minLon = lon - lonOffset
        val minLat = lat - latOffset
        val maxLon = lon + lonOffset
        val maxLat = lat + latOffset
        
        return doubleArrayOf(minLon, minLat, maxLon, maxLat)
    }

    private fun parseNotesJson(jsonString: String): List<Poi> {
        val pois = mutableListOf<Poi>()
        try {
            val jsonObject = JSONObject(jsonString)
            val features = jsonObject.optJSONArray("features") ?: return emptyList()

            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val geometry = feature.optJSONObject("geometry")
                val properties = feature.optJSONObject("properties")
                
                if (geometry == null || properties == null) continue

                val type = geometry.optString("type")
                if (type != "Point") continue

                val coordinates = geometry.optJSONArray("coordinates") ?: continue
                if (coordinates.length() < 2) continue
                
                val lon = coordinates.optDouble(0)
                val lat = coordinates.optDouble(1)
                
                val noteId = properties.optString("id", "")
                val status = properties.optString("status", "unknown")
                if (status != "open") continue // Only care about open notes

                val dateCreatedStr = properties.optString("date_created", "")
                val createdAt = try {
                    if (dateCreatedStr.isNotEmpty()) dateFormat.parse(dateCreatedStr) else Date()
                } catch (e: Exception) { Date() }

                val commentsArray = properties.optJSONArray("comments")
                val commentBuilder = StringBuilder()
                var descriptionText = "OSM Note #$noteId"

                if (commentsArray != null && commentsArray.length() > 0) {
                    for (j in 0 until commentsArray.length()) {
                        val comment = commentsArray.optJSONObject(j) ?: continue
                        val text = comment.optString("text", "")
                        val user = comment.optString("user", "Anonymous")
                        val date = comment.optString("date", "")
                        if (text.isNotEmpty()) {
                            if (j == 0) {
                                descriptionText = text 
                            }
                            commentBuilder.append("[$user at $date]:\n$text\n\n")
                        }
                    }
                }

                val notesText = commentBuilder.toString().trim()

                pois.add(
                    Poi(
                        id = "osm_note_$noteId",
                        name = "OSM Note ($noteId)",
                        description = descriptionText.take(200) + if (descriptionText.length > 200) "..." else "",
                        latitude = lat,
                        longitude = lon,
                        category = PoiCategory.UNKNOWN, 
                        createdAt = createdAt ?: Date(),
                        source = SOURCE_NOTES,
                        tags = mapOf("osm_id" to noteId, "status" to status),
                        notes = notesText,
                        priority = 0,
                        isActive = true
                    )
                )
            }
        } catch (e: JSONException) {
            Timber.e(e, "Failed to parse OSM Notes JSON")
        }
        return pois
    }
}
