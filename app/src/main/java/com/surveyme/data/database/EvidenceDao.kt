package com.surveyme.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.surveyme.data.model.Evidence
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {
    @Insert
    suspend fun insert(evidence: Evidence): Long

    @Query("SELECT * FROM evidence WHERE id = :id")
    suspend fun getById(id: Long): Evidence?

    /** What is pinned to one item, oldest first (the order it was gathered in). */
    @Query("SELECT * FROM evidence WHERE poiId = :poiId ORDER BY createdAt")
    fun observeForPoi(poiId: String): Flow<List<Evidence>>

    /** The review list: everything, newest first; the screen filters reviewed rows itself. */
    @Query("SELECT * FROM evidence ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Evidence>>

    @Query("SELECT COUNT(*) FROM evidence WHERE reviewed = 0")
    fun observeUnreviewedCount(): Flow<Int>

    @Query("UPDATE evidence SET reviewed = :reviewed WHERE id = :id")
    suspend fun setReviewed(id: Long, reviewed: Boolean)

    @Query("DELETE FROM evidence WHERE id = :id")
    suspend fun delete(id: Long)

    /** A placeholder note got its real id from osm.org: its evidence follows. */
    @Query("UPDATE evidence SET poiId = :newPoiId, noteId = :newNoteId WHERE poiId = :oldPoiId")
    suspend fun rekey(oldPoiId: String, newPoiId: String, newNoteId: Long?)
}
