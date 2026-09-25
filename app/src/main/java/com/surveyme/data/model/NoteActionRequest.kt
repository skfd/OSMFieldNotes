package com.surveyme.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One comment / close / reopen the user asked for, waiting to be posted to osm.org. Rows are
 * posted in [id] order (so two actions on one note keep their order) by
 * [com.surveyme.data.NoteActionQueue] and deleted once the server answered, or once it refused
 * for good. The details sheet renders these as "pending" thread entries; the note's own row in
 * `osm_notes` only carries the optimistic status.
 */
@Entity(tableName = "note_actions")
data class NoteActionRequest(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val noteId: Long,
    /** One of [NoteActionKind]'s api values: `comment`, `close`, `reopen`. */
    val action: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Posts tried so far; only soft failures (network, 5xx, 429) count, hard ones delete the row. */
    val attempts: Int = 0,
    val lastError: String? = null
) {
    val kind: NoteActionKind get() = NoteActionKind.fromApi(action)
}

/** What can be posted to `/api/0.6/notes/{id}/{apiValue}`. */
enum class NoteActionKind(val apiValue: String, val threadAction: NoteAction) {
    COMMENT("comment", NoteAction.COMMENTED),
    CLOSE("close", NoteAction.CLOSED),
    REOPEN("reopen", NoteAction.REOPENED);

    /** The status the note has once this action went through, or null when it does not change it. */
    val resultingStatus: NoteStatus?
        get() = when (this) {
            COMMENT -> null
            CLOSE -> NoteStatus.CLOSED
            REOPEN -> NoteStatus.OPEN
        }

    companion object {
        fun fromApi(value: String): NoteActionKind =
            entries.firstOrNull { it.apiValue == value } ?: COMMENT
    }
}
