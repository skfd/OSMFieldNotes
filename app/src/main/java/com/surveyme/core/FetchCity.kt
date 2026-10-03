package com.surveyme.core

/**
 * The city picked for "Whole city" fetching: an OSM boundary relation and its bounding box,
 * as Nominatim reported them when the user picked it. Fixed until the user picks a radius or
 * looks a city up again; walking out of it does not move it.
 *
 * Fixmes come from an Overpass area query on [relationId], so they follow the boundary. The
 * notes API only takes a box, so notes come from the bounding box and include the slices of
 * the neighbours that fall inside it; [contains] is that same box, so the stored set and the
 * fetched set agree.
 */
data class FetchCity(
    val relationId: Long,
    val name: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    val centreLat: Double get() = (south + north) / 2
    val centreLon: Double get() = (west + east) / 2

    /** Square degrees; the notes API refuses a box over [MAX_NOTES_BBOX_SQ_DEG]. */
    val areaSquareDegrees: Double get() = (north - south) * (east - west)

    fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east

    /** The fetch-centre key for this city; a different key is a refetch reason. */
    val areaKey: String get() = "city:$relationId"

    companion object {
        /** osm.org's `MAX_NOTE_REQUEST_AREA`; a bigger box answers 400. */
        const val MAX_NOTES_BBOX_SQ_DEG = 25.0

        fun radiusAreaKey(radiusMeters: Int) = "r$radiusMeters"
    }
}
