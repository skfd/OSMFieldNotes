package com.surveyme.data

import com.surveyme.BuildConfig
import com.surveyme.core.Constants
import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteActionKind
import com.surveyme.data.model.NoteComment
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import com.surveyme.data.model.Poi
import com.surveyme.data.model.PoiCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A non-2xx answer from osm.org or Overpass. [retryAfterSeconds] comes from the Retry-After
 * header when present; [body] is the server's text when it is short and human-readable (osm.org
 * answers a 409 with "The note 123 was closed at ..."), so the UI can show it.
 */
class FetchHttpException(val code: Int, val retryAfterSeconds: Long? = null, val body: String? = null) :
    java.io.IOException(
        "HTTP $code" + (retryAfterSeconds?.let { ", retry after ${it}s" } ?: "") + (body?.let { ": $it" } ?: "")
    )

/** One note from the API: the structured note plus the POI row that puts it on the map. */
data class FetchedNote(val note: OsmNote, val poi: Poi)

/**
 * A page of notes. [truncated] is true when the API's limit was hit, so the caller must not
 * treat "not in this list" as "gone from OSM".
 */
data class NotesPage(val notes: List<FetchedNote>, val truncated: Boolean)

/**
 * The osm.org notes API: bbox fetch and, for a logged-in user, comment / close / reopen.
 * [baseUrl] is [OsmAuthManager.BASE_URL] by default so a token from the dev server is never
 * sent to production (or the other way round); `www.openstreetmap.org` serves `/api/0.6` too.
 */
class OsmNotesService(private val baseUrl: String = OsmAuthManager.BASE_URL) {
    companion object {
        const val SOURCE_NOTES = "osm_notes"
        /** The API caps a bbox query; a 2 km square in a city holds a few dozen notes. */
        const val PAGE_LIMIT = 500
        /** The API's own maximum, for the whole-city box (Toronto: about 330). */
        const val CITY_PAGE_LIMIT = 10000
        private const val DESCRIPTION_MAX = 200
        private const val USER_AGENT = "OSMFieldNotes/${BuildConfig.VERSION_NAME}"
        private const val ERROR_BODY_MAX = 300
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
    ): NotesPage {
        val bbox = calculateBoundingBox(lat, lon, radiusMeters)
        return fetchNotesInBox(bbox[0], bbox[1], bbox[2], bbox[3], PAGE_LIMIT)
    }

    /**
     * Notes inside a box, for the whole-city fetch. Same answers and errors as [fetchNotes];
     * the API refuses a box over 25 square degrees with a 400 whose text says so.
     */
    suspend fun fetchNotesInBox(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        limit: Int = CITY_PAGE_LIMIT
    ): NotesPage = withContext(Dispatchers.IO) {
        // closed=N: also return notes closed within N days. Retention in the repository assumes
        // this window, so it is spelled out rather than left to the API default (also 7).
        val closedDays = Constants.NOTE_CLOSED_RETENTION_MILLIS / (24 * 60 * 60 * 1000L)
        val url = "$baseUrl/api/0.6/notes.json?bbox=$minLon,$minLat,$maxLon,$maxLat&limit=$limit&closed=$closedDays"

        Timber.d("Fetching OSM notes from API: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response)
            val responseBody = response.body?.string()
                ?: throw java.io.IOException("OSM Notes API response body is empty")
            parseNotesJson(responseBody, limit = limit)
        }
    }

    /**
     * One note by id, as the API has it now. Used to put a note back to the truth after a
     * refused action. Throws like [fetchNotes]; null when the answer was not a note.
     */
    suspend fun fetchNote(noteId: Long): FetchedNote? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/0.6/notes/$noteId.json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response)
            parseNoteJson(response.body?.string() ?: throw java.io.IOException("Empty note answer"))
        }
    }

    /**
     * `POST /api/0.6/notes/{id}/comment|close|reopen.json` with [text] as a form field (left
     * out when blank; the API needs text for a comment but not for close or reopen) and the
     * user's Bearer token. The answer is the whole note after the change, parsed with the same
     * code as a fetch, so the stored thread and status follow the server. Throws
     * [FetchHttpException] on a non-2xx answer (409 when the note is already in that state,
     * 401 when the token is dead) and [java.io.IOException] on network trouble.
     */
    suspend fun postNoteAction(
        noteId: Long,
        kind: NoteActionKind,
        text: String,
        authHeader: String
    ): FetchedNote = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
        if (text.isNotBlank()) form.add("text", text.trim())
        val request = Request.Builder()
            .url("$baseUrl/api/0.6/notes/$noteId/${kind.apiValue}.json")
            .header("User-Agent", USER_AGENT)
            .header("Authorization", authHeader)
            .post(form.build())
            .build()
        Timber.d("Posting ${kind.apiValue} to note $noteId at $baseUrl")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response)
            val body = response.body?.string() ?: throw java.io.IOException("Empty answer to ${kind.apiValue}")
            parseNoteJson(body) ?: throw java.io.IOException("Answer to ${kind.apiValue} was not a note")
        }
    }

    /**
     * `POST /api/0.6/notes.json?lat=&lon=&text=` (item 7). The API takes the three as query
     * parameters, not form fields; the body is empty. [authHeader] is the user's Bearer token,
     * or null to create the note anonymously, which osm.org allows. The answer is the new note,
     * parsed like a fetch, so the caller can store it with its real id. Throws like
     * [postNoteAction].
     */
    suspend fun createNote(
        lat: Double,
        lon: Double,
        text: String,
        authHeader: String?
    ): FetchedNote = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(createNoteUrl(lat, lon, text))
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().build())
        if (authHeader != null) builder.header("Authorization", authHeader)
        Timber.d("Creating a note at $lat, $lon on $baseUrl${if (authHeader == null) " anonymously" else ""}")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response)
            val body = response.body?.string() ?: throw java.io.IOException("Empty answer to create")
            parseNoteJson(body) ?: throw java.io.IOException("Answer to create was not a note")
        }
    }

    /** The create URL with lat, lon and text as encoded query parameters. Visible for the unit test. */
    internal fun createNoteUrl(lat: Double, lon: Double, text: String): HttpUrl =
        "$baseUrl/api/0.6/notes.json".toHttpUrl().newBuilder()
            .addQueryParameter("lat", String.format(Locale.US, "%.7f", lat))
            .addQueryParameter("lon", String.format(Locale.US, "%.7f", lon))
            .addQueryParameter("text", text.trim())
            .build()

    /** The error to throw for a non-2xx [response], carrying a short plain-text body if there is one. */
    private fun httpError(response: Response): FetchHttpException {
        val text = runCatching { response.body?.string() }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("<") }
            ?.take(ERROR_BODY_MAX)
        Timber.w("osm.org answered HTTP ${response.code}${text?.let { ": $it" } ?: ""}")
        return FetchHttpException(response.code, response.header("Retry-After")?.trim()?.toLongOrNull(), text)
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
    internal fun parseNotesJson(jsonString: String, now: Date = Date(), limit: Int = PAGE_LIMIT): NotesPage {
        val notes = mutableListOf<FetchedNote>()
        var featureCount = 0
        try {
            val jsonObject = JSONObject(jsonString)
            val features = jsonObject.optJSONArray("features") ?: return NotesPage(emptyList(), false)
            featureCount = features.length()
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                parseFeature(feature, now)?.let { notes.add(it) }
            }
        } catch (e: JSONException) {
            Timber.e(e, "Failed to parse OSM Notes JSON")
        }
        return NotesPage(notes, truncated = featureCount >= limit)
    }

    /** Parses the single Feature that `notes/{id}.json` and the comment/close/reopen posts answer. */
    internal fun parseNoteJson(jsonString: String, now: Date = Date()): FetchedNote? {
        return try {
            parseFeature(JSONObject(jsonString), now)
        } catch (e: JSONException) {
            Timber.e(e, "Failed to parse OSM note JSON")
            null
        }
    }

    /** One GeoJSON Feature of the notes API, or null when it is not a point note. */
    private fun parseFeature(feature: JSONObject, now: Date): FetchedNote? {
        val geometry = feature.optJSONObject("geometry")
        val properties = feature.optJSONObject("properties")
        if (geometry == null || properties == null) return null
        if (geometry.optString("type") != "Point") return null

        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        if (coordinates.length() < 2) return null
        val lon = coordinates.optDouble(0)
        val lat = coordinates.optDouble(1)

        val noteId = properties.optLong("id", -1L)
        if (noteId < 0) return null
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

        val note = OsmNote(
            noteId = noteId,
            poiId = OsmNote.poiIdFor(noteId),
            status = status,
            createdAt = createdAt,
            closedAt = closedAt,
            comments = comments,
            fetchedAt = now
        )
        return FetchedNote(note, poiFor(note, lat, lon))
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
