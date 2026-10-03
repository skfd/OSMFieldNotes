package com.surveyme.data

import com.surveyme.BuildConfig
import com.surveyme.core.FetchCity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Which city am I in" for the whole-city fetch: one Nominatim reverse lookup at city zoom
 * (10), made only when the user picks "Whole city" in Settings, never on a fetch tick, so the
 * one-request-per-second usage policy is never close.
 */
class CityLookup(private val baseUrl: String = "https://nominatim.openstreetmap.org") {

    /** A lookup that answered but gave nothing usable; [message] is for the user. */
    class NoCityException(message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** The boundary relation around ([lat], [lon]). Throws [NoCityException] or [IOException]. */
    suspend fun cityAt(lat: Double, lon: Double): FetchCity = withContext(Dispatchers.IO) {
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("lat", String.format(Locale.US, "%.6f", lat))
            .addQueryParameter("lon", String.format(Locale.US, "%.6f", lon))
            .addQueryParameter("zoom", "10")
            .build()
        Timber.d("City lookup: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OSMFieldNotes/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw FetchHttpException(response.code)
            parse(response.body?.string() ?: throw IOException("Empty answer from Nominatim"))
        }
    }

    /** One `/reverse?format=jsonv2` answer to a city, or [NoCityException]. Visible for the unit test. */
    internal fun parse(json: String): FetchCity {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IOException("Nominatim answered something that is not JSON")
        }
        o.optString("error").takeIf { it.isNotEmpty() }?.let { throw NoCityException("Nominatim: $it") }
        if (o.optString("osm_type") != "relation") {
            throw NoCityException("No city boundary here")
        }
        val box = o.optJSONArray("boundingbox")
        if (box == null || box.length() < 4) throw NoCityException("No bounding box for this place")
        // Nominatim orders it south, north, west, east, as strings.
        val city = FetchCity(
            relationId = o.optLong("osm_id"),
            name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') },
            south = box.getString(0).toDouble(),
            north = box.getString(1).toDouble(),
            west = box.getString(2).toDouble(),
            east = box.getString(3).toDouble()
        )
        if (city.areaSquareDegrees > FetchCity.MAX_NOTES_BBOX_SQ_DEG) {
            throw NoCityException("${city.name} is too large for the notes API")
        }
        return city
    }
}
