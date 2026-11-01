package dev.anilbeesetti.nextplayer.core.analysis.resolve

import dev.anilbeesetti.nextplayer.core.analysis.fingerprint.RepeatedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentResolverTest {

    private val durationMs = 24 * 60_000L

    private fun match(startMs: Long, endMs: Long, similarityPct: Int = 95) =
        RepeatedSegment(
            targetStartMs = startMs,
            targetEndMs = endMs,
            referenceStartMs = startMs,
            similarityPct = similarityPct,
        )

    // --- intro ---

    @Test
    fun `typical intro with one reference resolves`() {
        val consensus = SegmentConsensus.introConsensus(listOf(match(12_000, 100_000)))
        val intro = SegmentResolver.resolveIntro(durationMs, consensus)

        assertNotNullAnd(intro) {
            assertEquals(12_000L, it.startMs)
            assertEquals(100_000L, it.endMs)
            assertEquals(1, it.referenceCount)
            assertEquals(DetectedSources.AUDIO, it.sourcesMask)
        }
    }

    @Test
    fun `two agreeing references form a consensus and count as two`() {
        val consensus = SegmentConsensus.introConsensus(
            listOf(match(12_000, 100_000), match(13_500, 101_000)),
        )
        val intro = SegmentResolver.resolveIntro(durationMs, consensus)

        assertNotNullAnd(intro) {
            assertEquals(2, it.referenceCount)
            assertTrue(it.sourcesMask and DetectedSources.AUDIO != 0)
        }
    }

    @Test
    fun `disagreeing references fall back to a single-reference consensus`() {
        val consensus = SegmentConsensus.introConsensus(
            listOf(match(12_000, 100_000), match(240_000, 320_000)),
        )
        // Each side stands alone; the resolver still sees one candidate with a
        // single reference, which later passes through the standard guards.
        assertNotNullAnd(consensus) {
            assertEquals(1, it.referenceCount)
        }
    }

    @Test
    fun `short intro-looking block is rejected`() {
        val consensus = SegmentConsensus.introConsensus(listOf(match(5_000, 17_000)))
        assertNull(SegmentResolver.resolveIntro(durationMs, consensus))
    }

    @Test
    fun `intro ending in the second half of the video is rejected`() {
        val consensus = SegmentConsensus.introConsensus(listOf(match(60_000, 700_000)))
        assertNull(SegmentResolver.resolveIntro(durationMs, consensus))
    }

    @Test
    fun `agreeing chapter boundary adds chapter provenance`() {
        val consensus = SegmentConsensus.introConsensus(listOf(match(12_000, 100_000)))
        val intro = SegmentResolver.resolveIntro(durationMs, consensus, chapterEndMs = 101_500)
        assertNotNullAnd(intro) {
            assertEquals(
                DetectedSources.AUDIO or DetectedSources.CHAPTER,
                it.sourcesMask and (DetectedSources.AUDIO or DetectedSources.CHAPTER),
            )
        }
    }

    // --- outro ---

    @Test
    fun `outro reaching the end of file is marked eof`() {
        val consensus = SegmentConsensus.outroConsensus(listOf(match(1_380_000, 1_440_000)))
        val outro = SegmentResolver.resolveOutro(durationMs, consensus)

        assertNotNullAnd(outro) {
            assertEquals(1_380_000L, it.startMs)
            assertEquals(durationMs, it.endMs)
            assertTrue(it.endIsEof)
        }
    }

    @Test
    fun `outro with post credit scene keeps its own end`() {
        // Post-credit scene: matched credits run stops 40s before EOF.
        val consensus = SegmentConsensus.outroConsensus(listOf(match(1_380_000, 1_400_000)))
        val outro = SegmentResolver.resolveOutro(durationMs, consensus)

        assertNotNullAnd(outro) {
            assertEquals(1_400_000L, it.endMs)
            assertTrue(!it.endIsEof)
        }
    }

    @Test
    fun `repeated block at the start of the video is not an outro`() {
        val consensus = SegmentConsensus.outroConsensus(listOf(match(30_000, 90_000)))
        assertNull(SegmentResolver.resolveOutro(durationMs, consensus))
    }

    @Test
    fun `repeated block in the first half of the video is not an outro`() {
        val consensus = SegmentConsensus.outroConsensus(listOf(match(60_000, 120_000)))
        assertNull(SegmentResolver.resolveOutro(durationMs, consensus))
    }

    // --- helpers ---

    private fun <T> assertNotNullAnd(value: T?, assertion: (T) -> Unit) {
        if (value == null) throw AssertionError("expected non-null value")
        assertion(value)
    }
}
