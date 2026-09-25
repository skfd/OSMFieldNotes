package com.surveyme.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.surveyme.data.model.NoteStatus
import com.surveyme.data.model.OsmNote
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface NoteDao {
    @Query("SELECT * FROM osm_notes WHERE poiId = :poiId LIMIT 1")
    suspend fun getByPoiId(poiId: String): OsmNote?

    @Query("SELECT * FROM osm_notes WHERE poiId = :poiId LIMIT 1")
    fun observeByPoiId(poiId: String): Flow<OsmNote?>

    @Query("SELECT * FROM osm_notes WHERE noteId IN (:noteIds)")
    suspend fun getByNoteIds(noteIds: List<Long>): List<OsmNote>

    @Query("SELECT * FROM osm_notes WHERE poiId IN (:poiIds)")
    suspend fun getByPoiIds(poiIds: List<String>): List<OsmNote>

    /** Every stored note; the classifier's cluster rule needs the neighbourhood. */
    @Query("SELECT * FROM osm_notes")
    suspend fun getAll(): List<OsmNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<OsmNote>)

    /** The optimistic status while a close or reopen waits in the queue; the thread is untouched. */
    @Query("UPDATE osm_notes SET status = :status, closedAt = :closedAt WHERE noteId = :noteId")
    suspend fun setStatus(noteId: Long, status: NoteStatus, closedAt: Date?)

    @Query("DELETE FROM osm_notes WHERE poiId IN (:poiIds)")
    suspend fun deleteByPoiIds(poiIds: List<String>)

    @Query("DELETE FROM osm_notes")
    suspend fun deleteAll()
}
