package dev.anilbeesetti.nextplayer.feature.player.interpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FixedRateTimingTest {
    @Test fun twentyFourToSixtyUsesFractionalPositionsAndUniformTimestamps() {
        val timing = FixedRateTiming()
        assertEquals(listOf(0L), timing.next(0).map { it.timeUs })
        val first = timing.next(41_666)
        assertEquals(listOf(16_666L, 33_333L), first.map { it.timeUs })
        assertEquals(0.4f, first[0].fraction, 0.0001f)
        assertEquals(0.8f, first[1].fraction, 0.0001f)
        val second = timing.next(83_333)
        assertEquals(listOf(50_000L, 66_666L, 83_333L), second.map { it.timeUs })
        assertEquals(0.2f, second[0].fraction, 0.0001f)
        assertEquals(0.6f, second[1].fraction, 0.0001f)
        assertEquals(1f, second[2].fraction, 0f)
    }

    @Test fun oneHourHasNoRoundingDriftWithMillisecondQuantizedMkvFrames() {
        val timing = FixedRateTiming()
        var count = 0
        var previous = -1L
        for (source in 0..24 * 3600) {
            for (sample in timing.next(source * 1_000_000L / 24 / 1000 * 1000)) {
                assertEquals(count * 1_000_000L / 60, sample.timeUs)
                assertTrue(sample.timeUs > previous)
                assertTrue(sample.fraction in 0f..1f)
                previous = sample.timeUs
                count++
            }
        }
        assertEquals(60 * 3600 + 1, count)
        assertEquals(3_600_000_000L, previous)
    }

    @Test fun seekFlushAndLongGapsReanchorWithoutGeneratingABurst() {
        val timing = FixedRateTiming()
        timing.next(0)
        timing.next(41_666)
        assertEquals(listOf(FrameSample(20_000_000, 1f)), timing.next(20_000_000))
        assertEquals(listOf(FrameSample(1000, 1f)), timing.next(1000))
        timing.reset()
        assertEquals(listOf(FrameSample(10_000, 1f)), timing.next(10_000))
        assertTrue(timing.next(93_333).size <= 6)
    }

    @Test fun thirtyAndTwentyThreePointNineSevenSixAlsoUseSixtyHzGrid() {
        for (sourceRate in listOf(30.0, 24000.0 / 1001.0)) {
            val timing = FixedRateTiming()
            val frames = (0..300).flatMap { timing.next((it * 1_000_000 / sourceRate).toLong()) }
            frames.forEachIndexed { index, sample -> assertEquals(index * 1_000_000L / 60, sample.timeUs) }
        }
    }

    @Test fun sixtyHasNoDriftAndSupportsEveryAcceptedSourceRateWithinSixOutputs() {
        for (sourceRate in listOf(12.0, 24.0, 30.0, 24000.0 / 1001.0)) {
            val timing = FixedRateTiming()
            var count = 0L
            for (source in 0..3600) {
                val sourceTime = (source * 1_000_000L / sourceRate).toLong() / 1000 * 1000
                val samples = timing.next(sourceTime, 60)
                assertTrue("A quantized 12 fps interval produces at most six outputs", samples.size <= 6)
                for (sample in samples) {
                    assertEquals(count * 1_000_000L / 60, sample.timeUs)
                    assertTrue(sample.fraction in 0f..1f)
                    count++
                }
            }
            val lastTime = (3600 * 1_000_000.0 / sourceRate).toLong() / 1000 * 1000
            assertEquals(lastTime * 60 / 1_000_000 + 1, count)
        }
    }

    @Test fun resetAndSeekReanchorTheSixtyFpsGrid() {
        val timing = FixedRateTiming()
        timing.next(0, 60)
        timing.next(41_666, 60)
        timing.reset()
        assertEquals(listOf(FrameSample(166_666, 1f)), timing.next(166_666, 60))
        assertEquals(listOf(183_332L, 199_999L), timing.next(208_333, 60).map { it.timeUs })
        assertEquals(listOf(FrameSample(1000, 1f)), timing.next(1000, 60))
        timing.reset()
        assertEquals(listOf(FrameSample(5_000_000, 1f)), timing.next(5_000_000, 60))
    }

    @Test fun fixedTimingRejectsOtherRatesWithoutChangingTheCurrentGrid() {
        val timing = FixedRateTiming()
        timing.next(0)
        for (target in listOf(-1, 0, 24, 90, 120, Int.MAX_VALUE)) {
            try {
                timing.next(1_000_000, target)
                fail("Unsupported fixed target $target must be rejected")
            } catch (_: IllegalArgumentException) {
                // Validation must happen before changing the previous source timestamp.
            }
        }
        assertEquals(listOf(16_666L, 33_333L), timing.next(41_666).map { it.timeUs })
    }
}
