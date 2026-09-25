package com.surveyme.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A photo or a text note pinned to a note or fixme in the field (item 11), kept only on this
 * device for the desk session later. There is no foreign key to `pois` on purpose: the POI row
 * is expired when the walker moves on, and the evidence must outlive it, so the row carries a
 * snapshot of the item ([itemTitle], [itemSummary], [itemSource], [itemLat] / [itemLon]) that
 * stays meaningful after the POI is gone.
 *
 * [poiId] is the POI row's id at capture time, which for a queued create (item 7) is the
 * negative placeholder id; [com.surveyme.data.repository.PoiRepository] re-keys the row when
 * osm.org answers with the real id.
 */
@Entity(tableName = "evidence", indices = [Index("poiId"), Index("reviewed")])
data class Evidence(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val poiId: String,
    /** The OSM note id for a note (negative for a placeholder); null for a fixme or waypoint. */
    val noteId: Long? = null,
    /** [KIND_PHOTO] or [KIND_TEXT]. */
    val kind: String,
    val text: String? = null,
    /** Absolute path of the JPEG under the app's private files dir; null for text evidence. */
    val photoPath: String? = null,
    /** Where the walker stood at capture time; null when no fix was known. */
    val lat: Double? = null,
    val lon: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val reviewed: Boolean = false,
    /** Snapshot of the item, so the row reads well after the POI expired. */
    val itemTitle: String,
    val itemSummary: String,
    /** The POI's source at capture time: `osm_notes`, `osm_fixme`, ... */
    val itemSource: String,
    val itemLat: Double,
    val itemLon: Double
) {
    val isPhoto: Boolean get() = kind == KIND_PHOTO

    companion object {
        const val KIND_PHOTO = "photo"
        const val KIND_TEXT = "text"
    }
}
