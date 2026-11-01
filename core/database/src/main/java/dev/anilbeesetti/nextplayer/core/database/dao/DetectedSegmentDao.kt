package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.DetectedSegmentEntity

@Dao
interface DetectedSegmentDao {

    @Upsert
    suspend fun upsertAll(segments: List<DetectedSegmentEntity>)

    @Query("SELECT * FROM detected_segment WHERE uri = :uri")
    suspend fun get(uri: String): List<DetectedSegmentEntity>

    @Query("DELETE FROM detected_segment WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM detected_segment WHERE created_at < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
}
