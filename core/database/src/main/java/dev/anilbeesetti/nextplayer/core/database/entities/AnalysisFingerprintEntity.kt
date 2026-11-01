package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached raw audio fingerprints for the head and tail analysis windows of one
 * media file. Rows are keyed by uri and invalidated by the content identity
 * fields plus the algorithm version.
 */
@Entity(tableName = "analysis_fingerprint")
data class AnalysisFingerprintEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,
    @ColumnInfo(name = "content_size")
    val contentSize: Long,
    @ColumnInfo(name = "content_modified")
    val contentModified: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "audio_track_hash")
    val audioTrackHash: Int,
    @ColumnInfo(name = "algo_version")
    val algoVersion: Int,
    @ColumnInfo(name = "item_duration_ms")
    val itemDurationMs: Long,
    @ColumnInfo(name = "head_fingerprint")
    val headFingerprint: ByteArray,
    @ColumnInfo(name = "tail_fingerprint")
    val tailFingerprint: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
