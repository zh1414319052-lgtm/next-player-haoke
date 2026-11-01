package dev.anilbeesetti.nextplayer.feature.player.service

import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSkipSegmentsTest {
    private val preferences = PlayerPreferences(customSkipIntroOutro = true, customIntroSeconds = 90, customOutroSeconds = 60)

    @Test fun explicitDurationsWorkWithoutAnyDetectedChapters() {
        val segments = CustomSkipSegments.resolve(preferences, 951_409)
        assertEquals(listOf("INTRO", "OUTRO"), segments.map { it.kind })
        assertEquals(listOf(0L, 891_409L), segments.map { it.interval.startMs })
        assertEquals(listOf(90_000L, 951_409L), segments.map { it.interval.endMs })
        assertFalse(segments[0].interval.endIsEof)
        assertTrue(segments[1].interval.endIsEof)
    }

    @Test fun disabledUnknownShortAndOverlappingVideosStayIntact() {
        assertTrue(CustomSkipSegments.resolve(preferences.copy(customSkipIntroOutro = false), 951_409).isEmpty())
        listOf(Long.MIN_VALUE + 1, -1, 0, 30_000, 150_000, 150_999).forEach {
            assertTrue("duration=$it", CustomSkipSegments.resolve(preferences, it).isEmpty())
        }
        assertEquals(2, CustomSkipSegments.resolve(preferences, 151_000).size)
    }

    @Test fun zeroDisablesOneEndAndInvalidStoredNumbersStayBounded() {
        assertEquals(listOf("OUTRO"), CustomSkipSegments.resolve(preferences.copy(customIntroSeconds = 0), 951_409).map { it.kind })
        assertEquals(listOf("INTRO"), CustomSkipSegments.resolve(preferences.copy(customOutroSeconds = 0), 951_409).map { it.kind })
        assertTrue(CustomSkipSegments.resolve(preferences.copy(customIntroSeconds = -1, customOutroSeconds = -9), 951_409).isEmpty())
        assertEquals(1_800_000L, CustomSkipSegments.resolve(preferences.copy(customIntroSeconds = Int.MAX_VALUE, customOutroSeconds = 0), Long.MAX_VALUE).single().interval.endMs)
    }

    @Test fun undoManualReviewAndItemTransitionsUseTheSharedSuppressionState() {
        val segments = CustomSkipSegments.resolve(preferences, 951_409)
        val state = AutomaticSkipState().apply { update(segments) }
        assertEquals("INTRO", state.candidate(0, true, true, false)?.kind)
        state.suppress(segments[0])
        state.update(segments)
        assertEquals(null, state.candidate(5_000, true, true, false))
        state.manualSeek(900_000)
        assertEquals(null, state.candidate(900_000, true, true, false))
        state.reset()
        state.update(segments)
        assertEquals("INTRO", state.candidate(0, true, true, false)?.kind)
        assertEquals("OUTRO", state.candidate(900_000, true, true, false)?.kind)
        assertEquals(null, state.candidate(900_000, false, true, false))
        assertEquals(null, state.candidate(900_000, true, false, false))
        assertEquals(null, state.candidate(900_000, true, true, true))
    }

    @Test fun explicitDurationsOverrideOnlyTheirConfiguredEndWithoutChangingDetection() {
        val detected = CustomSkipSegments.resolve(preferences.copy(customIntroSeconds = 20, customOutroSeconds = 30), 951_409)
        val customIntro = preferences.copy(customOutroSeconds = 0)
        val merged = CustomSkipSegments.merge(detected, customIntro, 951_409)
        assertEquals(90_000L, merged.single { it.kind == "INTRO" }.interval.endMs)
        assertEquals(detected[1], merged.single { it.kind == "OUTRO" })
        assertEquals(detected, CustomSkipSegments.merge(detected, preferences.copy(customSkipIntroOutro = false), 951_409))
        assertTrue(CustomSkipSegments.merge(detected, preferences, 30_000).isEmpty())
    }
}
