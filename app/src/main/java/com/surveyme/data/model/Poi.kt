package com.surveyme.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date
import java.util.UUID

@Entity(tableName = "pois")
data class Poi(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val latitude: Double,
    val longitude: Double,
    val category: PoiCategory = PoiCategory.UNKNOWN,
    val notificationRadius: Int = 50, // meters
    val isVisited: Boolean = false,
    val isMuted: Boolean = false, // user asked never to be pinged for this one
    val lastNotificationTime: Long? = null,
    val createdAt: Date = Date(),
    val source: String = "manual", // manual, gpx, osm
    val tags: Map<String, String> = emptyMap(), // OSM-style tags
    val notes: String? = null,
    val priority: Int = 0, // 0 = normal, 1 = high, -1 = low
    val isActive: Boolean = true,
    /** OSM element behind a fixme (item 10): "node", "way" or "relation"; null for notes and waypoints. */
    val osmElementType: String? = null,
    val osmElementId: Long? = null,
    /** The element version Overpass reported (`out meta`), so an editor can be told what we saw. */
    val osmElementVersion: Long? = null
) {
    /** The element this POI stands for, from the columns or, for rows older than v7, the id string. */
    val element: com.surveyme.core.OsmElementRef?
        get() = com.surveyme.core.OsmElementRef.of(osmElementType, osmElementId)
            ?: com.surveyme.core.OsmElementRef.fromPoiId(id)

    fun distanceTo(lat: Double, lon: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            latitude, longitude,
            lat, lon,
            results
        )
        return results[0]
    }

    fun shouldNotify(currentTimeMillis: Long, cooldownMinutes: Int = 30): Boolean {
        if (!isActive) return false
        if (lastNotificationTime == null) return true

        val cooldownMillis = cooldownMinutes * 60 * 1000L
        return (currentTimeMillis - lastNotificationTime) > cooldownMillis
    }
}