package com.surveyme.data

import com.google.gson.Gson
import com.surveyme.data.model.OverpassResponse
import com.surveyme.data.model.Poi
import com.surveyme.data.model.PoiCategory
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

class OverpassService {
    companion object {
        /** Source tag the Notes tab filters on; must match PoiDao.getNotesAndFixmes. */
        const val SOURCE_FIXME = "osm_fixme"
        /** Source used by earlier builds for fixmes and bike share test data. Rows with it are purged. */
        const val SOURCE_LEGACY = "overpass"
    }


    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun fetchDockingStations(lat: Double, lon: Double, radius: Int = 1000): List<Poi> {
        // Query for bicycle rental docking stations (nodes and ways)
        // Using [out:json] and [timeout:25]
        // Getting center for ways to have a single coordinate
        val query = """
            [out:json][timeout:25];
            (
              node["bicycle_rental"="docking_station"](around:$radius,$lat,$lon);
              way["bicycle_rental"="docking_station"](around:$radius,$lat,$lon);
            );
            out center;
        """.trimIndent()

        val url = "https://overpass-api.de/api/interpreter?data=${java.net.URLEncoder.encode(query, "UTF-8")}"

        Timber.d("Fetching docking stations from Overpass API: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Overpass API request failed: ${response.code}")
                    return emptyList()
                }

                val responseBody = response.body?.string()
                if (responseBody == null) {
                    Timber.e("Overpass API response body is empty")
                    return emptyList()
                }

                val overpassResponse = gson.fromJson(responseBody, OverpassResponse::class.java)
                return parseOverpassResponse(
                    response = overpassResponse,
                    defaultName = "Docking Station",
                    prefixDesc = "Bicycle Rental Docking Station",
                    category = PoiCategory.PUBLIC_TRANSPORT,
                    source = "overpass"
                )
            }
        } catch (e: IOException) {
            Timber.e(e, "Error fetching data from Overpass API")
            return emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error parsing Overpass API response")
            return emptyList()
        }
    }

    fun fetchFixmePois(lat: Double, lon: Double, radius: Int = 1000): List<Poi> {
        val query = """
            [out:json][timeout:25];
            (
              node["fixme"](around:$radius,$lat,$lon);
              way["fixme"](around:$radius,$lat,$lon);
            );
            out center;
        """.trimIndent()

        val url = "https://overpass-api.de/api/interpreter?data=${java.net.URLEncoder.encode(query, "UTF-8")}"

        Timber.d("Fetching FIXME POIs from Overpass API: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Overpass API request failed: ${response.code}")
                    return emptyList()
                }

                val responseBody = response.body?.string()
                if (responseBody == null) {
                    Timber.e("Overpass API response body is empty")
                    return emptyList()
                }

                val overpassResponse = gson.fromJson(responseBody, OverpassResponse::class.java)
                return parseOverpassResponse(
                    response = overpassResponse,
                    defaultName = "FIXME Issue",
                    prefixDesc = "Needs fixing:",
                    category = PoiCategory.UNKNOWN,
                    source = SOURCE_FIXME
                )
            }
        } catch (e: IOException) {
            Timber.e(e, "Error fetching data from Overpass API")
            return emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error parsing Overpass API response")
            return emptyList()
        }
    }

    private fun parseOverpassResponse(
        response: OverpassResponse,
        defaultName: String,
        prefixDesc: String,
        category: PoiCategory,
        source: String
    ): List<Poi> {
        val pois = mutableListOf<Poi>()

        response.elements.forEach { element ->
            try {
                // Determine coordinates: use lat/lon for nodes, center.lat/center.lon for ways
                val lat = element.lat ?: element.center?.lat
                val lon = element.lon ?: element.center?.lon

                if (lat != null && lon != null) {
                    val name = element.tags?.get("name") ?: defaultName // Default name
                    val capacity = element.tags?.get("capacity")
                    val network = element.tags?.get("network")
                    val fixme = element.tags?.get("fixme")
                    
                    val description = buildString {
                        append(prefixDesc)
                        if (prefixDesc == "Needs fixing:") {
                            fixme?.let { append(" $it") }
                        } else {
                            capacity?.let { append("\nCapacity: $it") }
                            network?.let { append("\nNetwork: $it") }
                            fixme?.let { append("\nFixme: $it") }
                        }
                    }

                    val poi = Poi(
                        id = "overpass_${element.type}_${element.id}",
                        name = name,
                        description = description,
                        latitude = lat,
                        longitude = lon,
                        category = category, // Fits best for bicycle rental
                        source = source,
                        tags = element.tags ?: emptyMap()
                    )
                    pois.add(poi)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error parsing element ${element.id}")
            }
        }

        return pois
    }
}
