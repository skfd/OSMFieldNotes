package com.surveyme.data

import com.surveyme.core.Constants
import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteComment
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.data.model.PoiCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A non-2xx answer from osm.org or Overpass. [retryAfterSeconds] comes from the Retry-After header when present. */
class FetchHttpException(val code: Int, val retryAfterSeconds: Long? = null) :
    java.io.IOException("HTTP $code" + (retryAfterSeconds?.let { ", retry after ${it}s" } ?: ""))

/** One note from the API: the structured note plus the POI row that puts it on the map. */
data class FetchedNote(val note: OsmNote, val poi: Poi)

/**
 * A page of notes. [truncated] is true when the API's limit was hit, so the caller must not
 * treat "not in this list" as "gone from OSM".
 */
data class NotesPage(val notes: List<FetchedNote>, val truncated: Boolean)

class OsmNotesService {
    companion object {
        const val SOURCE_NOTES = "osm_notes"
        /** The API caps a bbox query; a 2 km square in a city holds a few dozen notes. */
        const val PAGE_LIMIT = 500
        private const val DESCRIPTION_MAX = 200
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // notes.json writes "2026-09-23 01:58:03 UTC"; the XML API writes ISO. Accept both, in UTC.
    private val apiDateFormats = listOf(
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

    /**
     * Notes inside the bounding square of [radiusMeters]: open ones and, per the API default,
     * ones closed within the last 7 days. Throws [FetchHttpException] on a non-2xx answer and
     * [java.io.IOException] on network trouble so the caller can back off; an empty page means
     * the area really has no notes.
     */
    suspend fun fetchNotes(
        lat: Double,
        lon: Double,
        radiusMeters: Int = 1000
    ): NotesPage = withContext(Dispatchers.IO) {
        val bbox = calculateBoundingBox(lat, lon, radiusMeters)
        // closed=N: also return notes closed within N days. Retention in the repository assumes
        // this window, so it is spelled out rather than left to the API default (also 7).
        val closedDays = Constants.NOTE_CLOSED_RETENTION_MILLIS / (24 * 60 * 60 * 1000L)
        val url = "https://api.openstreetmap.org/api/0.6/notes.json?bbox=${bbox[0]},${bbox[1]},${bbox[2]},${bbox[3]}&limit=$PAGE_LIMIT&closed=$closedDays"

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

    /** Parses a notes.json FeatureCollection. Visible for the item 17 unit tests. */
    internal fun parseNotesJson(jsonString: String, now: Date = Date()): NotesPage {
        val notes = mutableListOf<FetchedNote>()
        var featureCount = 0
        try {
            val jsonObject = JSONObject(jsonString)
            val features = jsonObject.optJSONArray("features") ?: return NotesPage(emptyList(), false)
            featureCount = features.length()

            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val geometry = feature.optJSONObject("geometry")
                val properties = feature.optJSONObject("properties")
                if (geometry == null || properties == null) continue
                if (geometry.optString("type") != "Point") continue

                val coordinates = geometry.optJSONArray("coordinates") ?: continue
                if (coordinates.length() < 2) continue
                val lon = coordinates.optDouble(0)
                val lat = coordinates.optDouble(1)

                val noteId = properties.optLong("id", -1L)
                if (noteId < 0) continue
                val status = NoteStatus.fromApi(properties.optString("status", "open"))
                val createdAt = parseApiDate(properties.optString("date_created", "")) ?: now
                val closedAt = parseApiDate(properties.optString("closed_at", ""))

                val comments = mutableListOf<NoteComment>()
                val commentsArray = properties.optJSONArray("comments")
                if (commentsArray != null) {
                    for (j in 0 until commentsArray.length()) {
                        val c = commentsArray.optJSONObject(j) ?: continue
                        comments.add(
                            NoteComment(
                                date = parseApiDate(c.optString("date", "")) ?: createdAt,
                                author = c.optString("user", "").takeIf { it.isNotEmpty() && c.has("user") },
                                uid = if (c.has("uid")) c.optLong("uid").takeIf { it > 0 } else null,
                                action = NoteAction.fromApi(c.optString("action", "")),
                                text = c.optString("text", "")
                            )
                        )
                    }
                }

                val poiId = OsmNote.poiIdFor(noteId)
                val note = OsmNote(
                    noteId = noteId,
                    poiId = poiId,
                    status = status,
                    createdAt = createdAt,
                    closedAt = closedAt,
                    comments = comments,
                    fetchedAt = now
                )
                notes.add(FetchedNote(note, poiFor(note, lat, lon)))
            }
        } catch (e: JSONException) {
            Timber.e(e, "Failed to parse OSM Notes JSON")
        }
        return NotesPage(notes, truncated = featureCount >= PAGE_LIMIT)
    }

    /**
     * The map row for a note. `description` is the opening comment (the ping shows its first
     * line), `notes` the thread flattened as a fallback for rows without a note row.
     */
    private fun poiFor(note: OsmNote, lat: Double, lon: Double): Poi {
        val opening = note.openingText.ifBlank { "OSM Note #${note.noteId}" }
        return Poi(
            id = note.poiId,
            name = "OSM Note (${note.noteId})",
            description = opening.take(DESCRIPTION_MAX) + if (opening.length > DESCRIPTION_MAX) "..." else "",
            latitude = lat,
            longitude = lon,
            category = PoiCategory.UNKNOWN,
            createdAt = note.createdAt,
            source = SOURCE_NOTES,
            tags = mapOf("osm_id" to note.noteId.toString()),
            notes = flattenThread(note),
            priority = 0,
            isActive = true
        )
    }

    private fun parseApiDate(value: String): Date? {
        if (value.isBlank()) return null
        for (format in apiDateFormats) {
            try {
                return synchronized(format) { format.parse(value) }
            } catch (_: Exception) {
            }
        }
        Timber.w("Unparsed note date: $value")
        return null
    }
}

/** The thread as plain text, one "[author at date]:" header per comment. */
fun flattenThread(note: OsmNote): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return note.comments
        .filter { it.text.isNotBlank() || it.action != NoteAction.COMMENTED }
        .joinToString("\n\n") { c ->
            val who = c.author ?: "Anonymous"
            val verb = when (c.action) {
                NoteAction.OPENED, NoteAction.COMMENTED -> ""
                NoteAction.CLOSED -> " closed"
                NoteAction.REOPENED -> " reopened"
                NoteAction.HIDDEN -> " hidden"
            }
            "[$who$verb at ${format.format(c.date)}]:\n${c.text}".trimEnd()
        }
}
