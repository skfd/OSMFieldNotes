package com.surveyme.core

import java.util.Locale

/**
 * A reference to one OSM element (node, way or relation) and the URLs an editor can be handed
 * (item 10). Pure Kotlin so the parsing and URL building are unit-testable; the Android side
 * that turns these into intents is `presentation/notes/ElementHandoff`.
 */
data class OsmElementRef(val type: String, val id: Long) {

    /** `node123`: the long form JOSM remote control and Vespucci expect in `select=`. */
    val longForm: String get() = "$type$id"

    /** The element's page on osm.org. */
    val browseUrl: String get() = "https://www.openstreetmap.org/$type/$id"

    /** osm.org's web editor (iD by default) with the element selected. */
    val editUrl: String get() = "https://www.openstreetmap.org/edit?$type=$id"

    /**
     * JOSM remote control `load_and_zoom` around ([lat], [lon]) with this element selected.
     * Vespucci registers `http://127.0.0.1:8111/...` (RemoteControlUrlActivity) and reads
     * `left`/`right` as longitudes, `top`/`bottom` as latitudes and `select` in the long form,
     * checked against its manifest and source on 2026-09-25. JOSM itself answers the same URL
     * on a desktop. [halfSizeDeg] is half the box side in degrees (0.001 is about 100 m).
     */
    fun josmRemoteUrl(lat: Double, lon: Double, halfSizeDeg: Double = 0.001): String {
        fun f(v: Double) = String.format(Locale.ROOT, "%.6f", v)
        return "http://127.0.0.1:8111/load_and_zoom" +
            "?left=${f(lon - halfSizeDeg)}&right=${f(lon + halfSizeDeg)}" +
            "&top=${f(lat + halfSizeDeg)}&bottom=${f(lat - halfSizeDeg)}" +
            "&select=$longForm"
    }

    companion object {
        val TYPES = setOf("node", "way", "relation")

        /** `overpass_{type}_{id}`, the POI id OverpassService has always written. */
        private val POI_ID = Regex("^overpass_(node|way|relation)_(\\d+)$")

        /** The first osm.org element link in free text (a note thread, say). */
        private val IN_TEXT = Regex(
            "https?://(?:www\\.)?(?:openstreetmap|osm)\\.org/(node|way|relation)/(\\d+)",
            RegexOption.IGNORE_CASE
        )

        fun of(type: String?, id: Long?): OsmElementRef? {
            if (type == null || id == null || id <= 0) return null
            val t = type.lowercase(Locale.ROOT)
            return if (t in TYPES) OsmElementRef(t, id) else null
        }

        fun fromPoiId(poiId: String): OsmElementRef? =
            POI_ID.find(poiId)?.let { OsmElementRef(it.groupValues[1], it.groupValues[2].toLong()) }

        fun firstInText(text: String?): OsmElementRef? {
            if (text.isNullOrEmpty()) return null
            val m = IN_TEXT.find(text) ?: return null
            return of(m.groupValues[1], m.groupValues[2].toLongOrNull())
        }

        /**
         * A `geo:` URI any map app answers (StreetComplete and Vespucci both register the
         * scheme; StreetComplete has no element-level intent, verified in its manifest).
         */
        fun geoUri(lat: Double, lon: Double, zoom: Int = 19): String =
            "geo:${String.format(Locale.ROOT, "%.6f", lat)},${String.format(Locale.ROOT, "%.6f", lon)}?z=$zoom"
    }
}
