package com.surveyme.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.surveyme.data.model.NoteActionRequest
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteActionDao {
    @Insert
    suspend fun insert(action: NoteActionRequest): Long

    /** Everything still to post, oldest first. The queue drains in this order. */
    @Query("SELECT * FROM note_actions ORDER BY id")
    suspend fun getAllOrdered(): List<NoteActionRequest>

    @Query("SELECT * FROM note_actions WHERE noteId = :noteId ORDER BY id")
    suspend fun getForNote(noteId: Long): List<NoteActionRequest>

    @Query("SELECT * FROM note_actions WHERE noteId IN (:noteIds) ORDER BY id")
    suspend fun getForNotes(noteIds: List<Long>): List<NoteActionRequest>

    @Query("SELECT * FROM note_actions WHERE noteId = :noteId ORDER BY id")
    fun observeForNote(noteId: Long): Flow<List<NoteActionRequest>>

    @Query("SELECT COUNT(*) FROM note_actions")
    suspend fun count(): Int

    @Query("UPDATE note_actions SET attempts = :attempts, lastError = :lastError WHERE id = :id")
    suspend fun markAttempt(id: Long, attempts: Int, lastError: String?)

    @Query("DELETE FROM note_actions WHERE id = :id")
    suspend fun delete(id: Long)
}
