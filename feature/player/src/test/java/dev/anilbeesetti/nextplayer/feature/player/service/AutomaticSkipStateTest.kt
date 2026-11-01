package dev.anilbeesetti.nextplayer.feature.player.service

import dev.anilbeesetti.nextplayer.core.analysis.resolve.DetectedInterval
import dev.anilbeesetti.nextplayer.core.analysis.resolve.DetectedSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticSkipStateTest {
    private val opening = AutomaticSkipState.Segment("INTRO", DetectedInterval(20_000, 90_000, false, DetectedSources.CHAPTER, 0, 0))
    private val credits = AutomaticSkipState.Segment("OUTRO", DetectedInterval(2_967_333, 3_247_125, false, DetectedSources.CHAPTER, 0, 0))

    @Test
    fun startsSkippingOnlyWhenPlaybackEntersTheChapter() {
        val state = AutomaticSkipState().apply { update(listOf(opening, credits)) }
        assertNull(state.candidate(19_999, true, true, false))
        assertEquals(opening, state.candidate(20_000, true, true, false))
        assertEquals(credits, state.candidate(2_967_333, true, true, false))
        assertNull(state.candidate(3_247_125, true, true, false))
    }

    @Test
    fun pausedBufferingAndScrubbingNeverTriggerSkipping() {
        val state = AutomaticSkipState().apply { update(listOf(opening)) }
        assertNull(state.candidate(30_000, false, true, false))
        assertNull(state.candidate(30_000, true, false, false))
        assertNull(state.candidate(30_000, true, true, true))
    }

    @Test
    fun undoAndManualRewatchSuppressOnlyTheChosenInterval() {
        val state = AutomaticSkipState().apply { update(listOf(opening, credits)) }
        state.suppress(opening)
        state.manualSeek(30_000)
        state.update(listOf(opening, credits))
        assertNull(state.candidate(30_000, true, true, false))
        assertEquals(credits, state.candidate(3_000_000, true, true, false))
    }

    @Test
    fun mediaTransitionResetsSuppression() {
        val state = AutomaticSkipState().apply { update(listOf(opening)); suppress(opening) }
        state.reset()
        state.update(listOf(opening))
        assertNotNull(state.candidate(30_000, true, true, false))
    }

    @Test
    fun seekingIntoACreditChapterLetsTheUserInspectIt() {
        val state = AutomaticSkipState().apply { update(listOf(credits)) }
        state.manualSeek(3_000_000)
        assertNull(state.candidate(3_000_000, true, true, false))
    }
}
