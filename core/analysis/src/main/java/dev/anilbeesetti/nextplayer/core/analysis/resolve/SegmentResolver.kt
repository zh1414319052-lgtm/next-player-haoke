package dev.anilbeesetti.nextplayer.core.analysis.resolve

import dev.anilbeesetti.nextplayer.core.analysis.fingerprint.RepeatedSegment

/** One skippable interval with its evidence provenance. */
data class DetectedInterval(
    val startMs: Long,
    val endMs: Long,
    val endIsEof: Boolean,
    val sourcesMask: Int,
    val referenceCount: Int,
    val similarityPct: Int,
)

/**
 * Combines repeated-audio matches from several reference episodes into one
 * consensus interval. Clusters matches whose target boundaries agree within
 * [toleranceMs] and keeps the largest cluster; single-reference matches stay
 * usable but are flagged with [DetectedInterval.referenceCount] == 1 so the
 * player can treat them more carefully.
 */
object SegmentConsensus {

    fun introConsensus(
        matches: List<RepeatedSegment>,
        toleranceMs: Long = 8_000,
    ): Consensus? = consensus(matches, toleranceMs, anchorIsEnd = true)

    fun outroConsensus(
        matches: List<RepeatedSegment>,
        toleranceMs: Long = 8_000,
    ): Consensus? = consensus(matches, toleranceMs, anchorIsEnd = false)

    data class Consensus(
        val startMs: Long,
        val endMs: Long,
        val similarityPct: Int,
        val referenceCount: Int,
    )

    private fun consensus(
        matches: List<RepeatedSegment>,
        toleranceMs: Long,
        anchorIsEnd: Boolean,
    ): Consensus? {
        if (matches.isEmpty()) return null

        val sorted = matches.sortedBy { if (anchorIsEnd) it.targetEndMs else it.targetStartMs }
        var bestCluster: List<RepeatedSegment> = emptyList()

        var cluster = mutableListOf<RepeatedSegment>()
        fun flush() {
            if (cluster.size > bestCluster.size ||
                (cluster.size == bestCluster.size && cluster.sumOf { it.similarityPct } > bestCluster.sumOf { it.similarityPct })
            ) {
                bestCluster = cluster.toList()
            }
        }

        var anchor = Long.MIN_VALUE
        for (match in sorted) {
            val value = if (anchorIsEnd) match.targetEndMs else match.targetStartMs
            if (cluster.isEmpty() || value - anchor <= toleranceMs) {
                cluster += match
                anchor = value
            } else {
                flush()
                cluster = mutableListOf(match)
                anchor = value
            }
        }
        flush()

        if (bestCluster.isEmpty()) return null
        val starts = bestCluster.map { it.targetStartMs }.sorted()
        val ends = bestCluster.map { it.targetEndMs }.sorted()
        return Consensus(
            startMs = median(starts),
            endMs = median(ends),
            similarityPct = bestCluster.minOf { it.similarityPct },
            referenceCount = bestCluster.size,
        )
    }

    private fun <T : Comparable<T>> median(values: List<T>): T {
        require(values.isNotEmpty())
        return values[values.size / 2]
    }
}

/**
 * Turns consensus evidence into final skip intervals, applying the guards that
 * keep story content and post-credit scenes safe. All thresholds live here so
 * they are visible and testable in one place.
 */
object SegmentResolver {

    // Intro guards.
    const val MIN_INTRO_LENGTH_MS = 15_000L
    const val MAX_INTRO_LENGTH_MS = 5 * 60_000L
    const val MAX_INTRO_END_FRACTION = 0.45
    const val MAX_INTRO_START_MS = 15 * 60_000L

    // Outro guards.
    const val MIN_OUTRO_LENGTH_MS = 10_000L
    const val MIN_OUTRO_START_MS = 60_000L
    const val MIN_OUTRO_START_FRACTION = 0.5
    const val EOF_TOLERANCE_MS = 2_500L

    // A chapter boundary within this distance of the audio evidence marks the
    // chapter channel as agreeing (it never overrides audio evidence alone).
    const val CHAPTER_AGREEMENT_MS = 8_000L

    /**
     * Resolves the intro interval. Audio evidence is mandatory; a chapter
     * boundary near the detected end only adds confidence.
     */
    fun resolveIntro(
        durationMs: Long,
        consensus: SegmentConsensus.Consensus?,
        chapterEndMs: Long? = null,
    ): DetectedInterval? {
        consensus ?: return null
        val start = consensus.startMs
        val end = consensus.endMs

        val valid = end - start >= MIN_INTRO_LENGTH_MS &&
            end - start <= MAX_INTRO_LENGTH_MS &&
            start >= 0 &&
            start <= MAX_INTRO_START_MS &&
            end <= durationMs * MAX_INTRO_END_FRACTION &&
            end <= durationMs
        if (!valid) return null

        var sources = DetectedSources.AUDIO
        if (chapterEndMs != null && kotlin.math.abs(chapterEndMs - end) <= CHAPTER_AGREEMENT_MS) {
            sources = sources or DetectedSources.CHAPTER
        }
        return DetectedInterval(
            startMs = start,
            endMs = end,
            endIsEof = false,
            sourcesMask = sources,
            referenceCount = consensus.referenceCount,
            similarityPct = consensus.similarityPct,
        )
    }

    /**
     * Resolves the outro interval. Audio evidence is mandatory. When the match
     * reaches the end of the file the interval is marked as ending at EOF;
     * otherwise the detected end is preserved so post-credit scenes survive.
     */
    fun resolveOutro(
        durationMs: Long,
        consensus: SegmentConsensus.Consensus?,
        chapterStartMs: Long? = null,
    ): DetectedInterval? {
        consensus ?: return null
        val start = consensus.startMs
        val end = consensus.endMs

        val valid = end - start >= MIN_OUTRO_LENGTH_MS &&
            start >= MIN_OUTRO_START_MS &&
            start >= durationMs * MIN_OUTRO_START_FRACTION &&
            end <= durationMs + EOF_TOLERANCE_MS
        if (!valid) return null

        val endIsEof = end >= durationMs - EOF_TOLERANCE_MS
        var sources = DetectedSources.AUDIO
        if (chapterStartMs != null && kotlin.math.abs(chapterStartMs - start) <= CHAPTER_AGREEMENT_MS) {
            sources = sources or DetectedSources.CHAPTER
        }
        return DetectedInterval(
            startMs = start,
            endMs = if (endIsEof) durationMs else end,
            endIsEof = endIsEof,
            sourcesMask = sources,
            referenceCount = consensus.referenceCount,
            similarityPct = consensus.similarityPct,
        )
    }
}

/** Evidence source bits, mirroring [dev.anilbeesetti.nextplayer.core.database.entities.DetectedSegmentEntity]. */
object DetectedSources {
    const val AUDIO = 1
    const val CHAPTER = 2
    const val FRAME_STATISTICS = 4
    const val TEXT_DETECTION = 8
}
