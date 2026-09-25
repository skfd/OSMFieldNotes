package com.surveyme.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * An OSM note as the notes API describes it. The POI row with id [poiId] is the thing on the
 * map (position, ping flags); this row holds what the note *is*: its status, dates and thread,
 * plus the app's own [labels] and [surveyAfter] (reserved for classification, roadmap item 8).
 *
 * No foreign key on purpose: `insertPois` uses REPLACE, which would cascade-delete this row on
 * every refetch. [com.surveyme.data.repository.PoiRepository] deletes both rows together.
 */
@Entity(
    tableName = "osm_notes",
    indices = [Index(value = ["poiId"], unique = true)]
)
data class OsmNote(
    @PrimaryKey
    val noteId: Long,
    val poiId: String,
    val status: NoteStatus,
    val createdAt: Date,
    val closedAt: Date? = null,
    val comments: List<NoteComment> = emptyList(),
    /** Classification labels, item 8. Empty until then. */
    val labels: Set<String> = emptySet(),
    /** Strict `#surveyafter` date, item 8. Hidden from pings until then. */
    val surveyAfter: Date? = null,
    /** When the API last returned this note. */
    val fetchedAt: Date = Date()
) {
    val isOpen: Boolean get() = status == NoteStatus.OPEN

    /**
     * A note the user created that osm.org has not answered yet (item 7): a local placeholder
     * with a negative id, replaced by the server's note once the queued create goes through.
     */
    val isPending: Boolean get() = noteId < 0

    /** Text of the opening comment, or an empty string for a note whose thread is not stored yet. */
    val openingText: String get() = comments.firstOrNull()?.text ?: ""

    /** The osm.org page for this note. */
    val url: String get() = "https://www.openstreetmap.org/note/$noteId"

    companion object {
        const val POI_ID_PREFIX = "osm_note_"
        fun poiIdFor(noteId: Long) = "$POI_ID_PREFIX$noteId"

        /** A fresh negative id for a placeholder; unique enough for one device. */
        fun placeholderId(now: Long = System.currentTimeMillis()): Long = -now
    }
}

enum class NoteStatus(val apiValue: String) {
    OPEN("open"),
    CLOSED("closed"),
    HIDDEN("hidden");

    companion object {
        fun fromApi(value: String?): NoteStatus =
            entries.firstOrNull { it.apiValue == value } ?: OPEN
    }
}

enum class NoteAction(val apiValue: String) {
    OPENED("opened"),
    COMMENTED("commented"),
    CLOSED("closed"),
    REOPENED("reopened"),
    HIDDEN("hidden");

    companion object {
        fun fromApi(value: String?): NoteAction =
            entries.firstOrNull { it.apiValue == value } ?: COMMENTED
    }
}

/** One entry of a note's thread. [author] and [uid] are null for anonymous comments. */
data class NoteComment(
    val date: Date,
    val author: String?,
    val uid: Long?,
    val action: NoteAction,
    val text: String
)
