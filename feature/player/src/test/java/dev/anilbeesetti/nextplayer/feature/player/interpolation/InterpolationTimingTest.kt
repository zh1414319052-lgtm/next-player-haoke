package dev.anilbeesetti.nextplayer.feature.player.interpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpolationTimingTest {
    @Test fun preserves24FpsTimelineWithoutRoundingDrift() {
        val timing = InterpolationTiming()
        assertNull(timing.next(0))
        assertEquals(20_833L, timing.next(41_666))
        assertEquals(62_499L, timing.next(83_333))
        assertEquals(104_166L, timing.next(125_000))
    }

    @Test fun neverBridgesSeeksOrDuplicateFrames() {
        val timing = InterpolationTiming()
        timing.next(0)
        assertNull(timing.next(0))
        assertNull(timing.next(5_000_000))
        assertNull(timing.next(1_000_000))
        assertEquals(1_020_833L, timing.next(1_041_666))
        timing.reset()
        assertNull(timing.next(1_083_333))
    }

    @Test fun skipsHighFrameRatesAndLongGaps() {
        val timing = InterpolationTiming()
        timing.next(0)
        assertNull(timing.next(16_666))
        assertNull(timing.next(116_666))
        assertEquals(133_332L, timing.next(149_999))
    }
}
