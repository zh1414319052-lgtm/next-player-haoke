package dev.anilbeesetti.nextplayer.core.analysis.fingerprint

/**
 * Finds repeated audio segments between two fingerprint windows by exhaustive
 * signed-offset search with per-item Hamming distance, gap bridging and run
 * scoring.
 *
 * Signed offsets matter: the repeated block (an opening, an ending) does not
 * necessarily sit at the same position in both episodes, because cold opens and
 * recaps differ in length. A negative offset simply means the block starts
 * earlier in the target than in the reference window.
 *
 * This mirrors the approach used by the maintained open-source skip tools
 * (Chromaprint fingerprints + offset search + consecutive run detection), but is
 * deliberately conservative: a run is only reported when a large majority of
 * items match and the run is long enough that it cannot be a common logo or a
 * single repeated sound effect.
 */
object RepeatedSegmentMatcher {

    /** Maximum bits differing for a single fingerprint item to count as matched. */
    const val DEFAULT_MAX_BIT_DISTANCE = 12

    /** Share of items that must match inside a reported run. */
    const val DEFAULT_MIN_RUN_SIMILARITY_PCT = 85

    /** Consecutive unmatched items tolerated inside a run before it is closed. */
    const val MAX_GAP_ITEMS = 3

    /** Number of coarse candidates refined in the second pass. */
    const val LEADER_COUNT = 6

    /**
     * Finds the best repeated segment of [target] inside [reference], scanning
     * every alignment offset where at least [minRunItems] items overlap.
     *
     * @param offsetStride stride of the coarse offset scan; every offset within
     * one stride of a coarse leader is then examined individually.
     */
    fun findLongestRepeat(
        target: AudioFingerprint,
        reference: AudioFingerprint,
        minRunItems: Int,
        maxBitDistance: Int = DEFAULT_MAX_BIT_DISTANCE,
        minRunSimilarityPct: Int = DEFAULT_MIN_RUN_SIMILARITY_PCT,
        offsetStride: Int = 2,
    ): RepeatedSegment? {
        if (target.isEmpty() || reference.isEmpty() || target.itemDurationMs != reference.itemDurationMs) {
            return null
        }
        if (target.size < minRunItems || reference.size < minRunItems) {
            return null
        }

        val itemMs = target.itemDurationMs
        // offset d means: target[i] compares against reference[d + i].
        // i runs in [max(0, -d), min(target.size, reference.size - d)).
        val minOffset = -(target.size - minRunItems)
        val maxOffset = reference.size - minRunItems
        if (minOffset > maxOffset) return null

        val stride = offsetStride.coerceAtLeast(1)
        val coarse = collectRuns(target, reference, minOffset, maxOffset, stride, maxBitDistance, minRunItems)
        if (coarse.isEmpty()) return null

        val leaders = coarse.sortedByDescending { it.rank }.take(LEADER_COUNT)
        val examined = BooleanArray(maxOffset - minOffset + 1)
        val runs = mutableListOf<Run>()
        for (leader in leaders) {
            val from = (leader.offset - stride).coerceAtLeast(minOffset)
            val to = (leader.offset + stride).coerceAtMost(maxOffset)
            for (offset in from..to) {
                val index = offset - minOffset
                if (examined[index]) continue
                examined[index] = true
                runForOffset(target, reference, offset, maxBitDistance, minRunItems)?.let { runs += it }
            }
        }

        val bestRun = (runs + coarse.filter { !examined[it.offset - minOffset] })
            .filter { it.score >= minRunSimilarityPct }
            .maxByOrNull { it.rank }
            ?: return null

        return RepeatedSegment(
            targetStartMs = target.windowStartMs + bestRun.start * itemMs,
            targetEndMs = target.windowStartMs + bestRun.endExclusive * itemMs,
            referenceStartMs = reference.windowStartMs + (bestRun.offset + bestRun.start) * itemMs,
            similarityPct = bestRun.score,
        )
    }

    private fun collectRuns(
        target: AudioFingerprint,
        reference: AudioFingerprint,
        fromOffset: Int,
        toOffset: Int,
        stride: Int,
        maxBitDistance: Int,
        minRunItems: Int,
    ): List<Run> {
        val runs = mutableListOf<Run>()
        var offset = fromOffset
        while (offset <= toOffset) {
            runForOffset(target, reference, offset, maxBitDistance, minRunItems)?.let { runs += it }
            offset += stride
        }
        return runs
    }

    /**
     * Computes the best run for one alignment offset. Items match when their
     * Hamming distance is within [maxBitDistance]; short gaps are bridged. The
     * returned run maximises score (percentage) first, length second.
     */
    private fun runForOffset(
        target: AudioFingerprint,
        reference: AudioFingerprint,
        offset: Int,
        maxBitDistance: Int,
        minRunItems: Int,
    ): Run? {
        val startIndex = maxOf(0, -offset)
        val endIndex = minOf(target.size, reference.size - offset)
        val overlap = endIndex - startIndex
        if (overlap < minRunItems) return null

        val tv = target.values
        val rv = reference.values
        var runStart = -1
        var matched = 0
        var gap = 0
        var best: Run? = null
        var bestRank = -1L

        fun closeRun(endExclusive: Int) {
            if (runStart < 0) return
            val length = endExclusive - runStart
            if (length >= minRunItems) {
                val score = (matched * 100L / length).toInt()
                val rank = score.toLong() * 1_000_000L + length
                if (rank > bestRank) {
                    bestRank = rank
                    best = Run(offset, runStart, endExclusive, score)
                }
            }
            runStart = -1
            matched = 0
            gap = 0
        }

        for (i in startIndex until endIndex) {
            val distance = Integer.bitCount(tv[i] xor rv[offset + i])
            if (distance <= maxBitDistance) {
                if (runStart < 0) runStart = i
                matched++
                gap = 0
            } else if (runStart >= 0) {
                gap++
                if (gap > MAX_GAP_ITEMS) closeRun(i - gap + 1)
            }
        }
        closeRun(endIndex)
        return best
    }

    private class Run(
        val offset: Int,
        val start: Int,
        val endExclusive: Int,
        val score: Int,
    ) {
        val rank: Long get() = score.toLong() * 1_000_000L + (endExclusive - start)
    }
}
