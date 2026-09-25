package com.surveyme.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One comment / close / reopen / create the user asked for, waiting to be posted to osm.org.
 * Rows are posted in [id] order (so two actions on one note keep their order) by
 * [com.surveyme.data.NoteActionQueue] and deleted once the server answered, or once it refused
 * for good. The details sheet renders these as "pending" thread entries; the note's own row in
 * `osm_notes` only carries the optimistic status.
 *
 * A `create` row (item 7) has no note yet: [noteId] is the negative placeholder id of the local
 * note and POI rows shown until osm.org answers, and [lat] / [lon] say where the note goes.
 */
@Entity(tableName = "note_actions")
data class NoteActionRequest(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val noteId: Long,
    /** One of [NoteActionKind]'s api values: `comment`, `close`, `reopen`, `create`. */
    val action: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Posts tried so far; only soft failures (network, 5xx, 429) count, hard ones delete the row. */
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Where a `create` puts the note; null for the other kinds (DB v6). */
    val lat: Double? = null,
    val lon: Double? = null
) {
    val kind: NoteActionKind get() = NoteActionKind.fromApi(action)
}

/**
 * What can be posted: `/api/0.6/notes/{id}/{apiValue}` for the first three, `/api/0.6/notes`
 * for [CREATE].
 */
enum class NoteActionKind(val apiValue: String, val threadAction: NoteAction) {
    COMMENT("comment", NoteAction.COMMENTED),
    CLOSE("close", NoteAction.CLOSED),
    REOPEN("reopen", NoteAction.REOPENED),
    CREATE("create", NoteAction.OPENED);

    /** The status the note has once this action went through, or null when it does not change it. */
    val resultingStatus: NoteStatus?
        get() = when (this) {
            COMMENT, CREATE -> null
            CLOSE -> NoteStatus.CLOSED
            REOPEN -> NoteStatus.OPEN
        }

    companion object {
        fun fromApi(value: String): NoteActionKind =
            entries.firstOrNull { it.apiValue == value } ?: COMMENT
    }
}
