package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.AnalysisFingerprintEntity

@Dao
interface AnalysisFingerprintDao {

    @Upsert
    suspend fun upsert(entity: AnalysisFingerprintEntity)

    @Query("SELECT * FROM analysis_fingerprint WHERE uri = :uri")
    suspend fun get(uri: String): AnalysisFingerprintEntity?

    @Query("SELECT * FROM analysis_fingerprint WHERE uri IN (:uris)")
    suspend fun getAll(uris: List<String>): List<AnalysisFingerprintEntity>

    @Query("DELETE FROM analysis_fingerprint WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("SELECT uri FROM analysis_fingerprint ORDER BY created_at ASC")
    suspend fun allUrisOldestFirst(): List<String>

    @Query("DELETE FROM analysis_fingerprint WHERE created_at < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
}
