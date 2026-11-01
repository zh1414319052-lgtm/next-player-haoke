package dev.anilbeesetti.nextplayer.core.analysis.fingerprint

/**
 * A raw Chromaprint fingerprint computed over a finite window of a media file.
 *
 * @param values raw fingerprint items (uint32 values stored in Int)
 * @param itemDurationMs duration in milliseconds of one fingerprint item
 * @param windowStartMs media timestamp of the first item inside the source file
 */
class AudioFingerprint(
    val values: IntArray,
    val itemDurationMs: Long,
    val windowStartMs: Long,
) {
    val size: Int get() = values.size

    /** Media timestamp of the end of the window covered by this fingerprint. */
    fun windowEndMs(): Long = windowStartMs + size * itemDurationMs

    fun isEmpty(): Boolean = size == 0
}

/**
 * One repeated audio segment found between a target window and a reference window.
 *
 * [targetStartMs]/[targetEndMs] are absolute media timestamps inside the target file;
 * [referenceStartMs] is the media timestamp where the same block starts in the
 * reference file. [similarityPct] is the share of fingerprint items matched
 * within [targetStartMs, targetEndMs).
 */
data class RepeatedSegment(
    val targetStartMs: Long,
    val targetEndMs: Long,
    val referenceStartMs: Long,
    val similarityPct: Int,
)
