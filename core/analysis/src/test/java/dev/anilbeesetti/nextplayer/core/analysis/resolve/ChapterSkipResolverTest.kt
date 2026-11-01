package dev.anilbeesetti.nextplayer.core.analysis.resolve

import dev.anilbeesetti.nextplayer.core.analysis.resolve.ChapterSkipResolver.ChapterMarker
import dev.anilbeesetti.nextplayer.core.analysis.resolve.ChapterSkipResolver.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSkipResolverTest {
    @Test
    fun visionQuestCreditsStopAtTheFollowingScene() {
        val chapters = listOf(
            ChapterMarker("Recap", 0),
            ChapterMarker("Scene", 87_000),
            ChapterMarker("Credits", 2_967_000),
            ChapterMarker("Scene", 3_247_000),
        )
        val result = ChapterSkipResolver.resolve(chapters, 3_400_000)
        assertEquals(1, result.size)
        assertEquals(Kind.OUTRO, result.single().kind)
        assertEquals(2_967_000L, result.single().interval.startMs)
        assertEquals(3_247_000L, result.single().interval.endMs)
        assertFalse(result.single().interval.endIsEof)
    }

    @Test
    fun openingPreservesTheColdOpen() {
        val result = ChapterSkipResolver.resolve(
            listOf(ChapterMarker("Scene", 0), ChapterMarker("OP 1", 120_000), ChapterMarker("Scene", 210_000)),
            1_400_000,
        )
        assertEquals(120_000L, result.single().interval.startMs)
        assertEquals(210_000L, result.single().interval.endMs)
    }

    @Test
    fun finalCreditsReachEof() {
        val result = ChapterSkipResolver.resolve(listOf(ChapterMarker("End Credits", 1_300_000)), 1_440_000)
        assertTrue(result.single().interval.endIsEof)
    }

    @Test
    fun recapEpisodeAndOrdinaryScenesAreNotOpenings() {
        val chapters = listOf("Recap", "Episode 1", "Shop", "Scene", "Previously On", "Introduction to physics")
            .mapIndexed { i, title -> ChapterMarker(title, i * 30_000L) }
        assertTrue(ChapterSkipResolver.resolve(chapters, 1_440_000).isEmpty())
    }

    @Test
    fun conflictingChapterTitlesFailClosed() {
        val result = ChapterSkipResolver.resolve(
            listOf(ChapterMarker("Opening", 0), ChapterMarker("Scene", 0), ChapterMarker("Scene", 90_000)),
            1_400_000,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun shorterExplicitEndIsPreserved() {
        val result = ChapterSkipResolver.resolve(
            listOf(ChapterMarker("Credits", 1_300_000, 1_330_000), ChapterMarker("Scene", 1_350_000)),
            1_400_000,
        )
        assertEquals(1_330_000L, result.single().interval.endMs)
        assertFalse(result.single().interval.endIsEof)
    }

    @Test
    fun hiddenDurationAndShortIntroCannotAuthorizeSkip() {
        assertTrue(ChapterSkipResolver.resolve(listOf(ChapterMarker("Opening", 0)), -1).isEmpty())
        assertTrue(
            ChapterSkipResolver.resolve(listOf(ChapterMarker("Opening", 0), ChapterMarker("Scene", 2_000)), 1_400_000).isEmpty(),
        )
    }
}
