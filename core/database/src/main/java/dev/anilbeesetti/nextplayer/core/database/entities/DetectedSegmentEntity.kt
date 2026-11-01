package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One detected skippable segment (intro or outro) for a media file. [kind] is
 * one of [DetectedSegmentEntity.KIND_INTRO] / [DetectedSegmentEntity.KIND_OUTRO].
 *
 * Provenance is stored so the player can decide how much to trust a segment:
 * [sourcesMask] records which evidence channels agreed, [referenceCount] how
 * many reference episodes produced the same interval and [similarityPct] the
 * fingerprint match quality.
 */
@Entity(tableName = "detected_segment", primaryKeys = ["uri", "kind"])
data class DetectedSegmentEntity(
    @ColumnInfo(name = "uri")
    val uri: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "start_ms")
    val startMs: Long,
    @ColumnInfo(name = "end_ms")
    val endMs: Long,
    @ColumnInfo(name = "end_is_eof")
    val endIsEof: Boolean,
    @ColumnInfo(name = "sources_mask")
    val sourcesMask: Int,
    @ColumnInfo(name = "reference_count")
    val referenceCount: Int,
    @ColumnInfo(name = "similarity_pct")
    val similarityPct: Int,
    @ColumnInfo(name = "algo_version")
    val algoVersion: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    companion object {
        const val KIND_INTRO = "INTRO"
        const val KIND_OUTRO = "OUTRO"

        // Evidence source bits for [sourcesMask].
        const val SOURCE_AUDIO_MATCH = 1
        const val SOURCE_CHAPTER = 2
        const val SOURCE_FRAME_STATISTICS = 4
        const val SOURCE_TEXT_DETECTION = 8
    }
}
