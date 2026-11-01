package dev.anilbeesetti.nextplayer.feature.player.interpolation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class InterpolationReleaseTraceTest {
    @Test
    fun traceKeepsOnlyTheLatest128MatchedSubmissionsWithoutChangingTimesOrCounters() {
        val state = InterpolationState().apply { setEnabled(true) }
        for (index in 0L until 150L) {
            val timeUs = index * 11_111L
            val releaseTimeNs = 5_000_000_000L + index * 11_111_111L
            val callbackTimeNs = releaseTimeNs - 3_000_000L
            state.recordOutput(timeUs, synthetic = index % 2 == 0L)
            state.onFrameSubmitted(timeUs, releaseTimeNs, callbackTimeNs)
        }

        val trace = checkNotNull(state.snapshot().getLongArray("releaseTrace"))
        assertEquals(128 * 3, trace.size)
        val expected = (22L until 150L).flatMap { index ->
            val releaseTimeNs = 5_000_000_000L + index * 11_111_111L
            listOf(index * 11_111L, releaseTimeNs, releaseTimeNs - 3_000_000L)
        }.toLongArray()
        assertArrayEquals("Media microseconds and release/callback nanoseconds stay unmodified", expected, trace)
        assertEquals(150L, state.emitted.get())
        assertEquals(150L, state.presented.get())
        assertEquals(75L, state.generated.get())
        assertEquals(75L, state.submitted.get())
    }

    @Test
    fun onlyMatchedPositiveReleaseTimesEnterTraceAndLegacyCountingStillWorks() {
        val state = InterpolationState().apply { setEnabled(true) }
        state.recordOutput(0, synthetic = false)
        state.onFrameSubmitted(0, 100, 90)
        state.recordOutput(10, synthetic = true)
        state.onFrameSubmitted(10) // Existing callers without release times still count normally.
        state.recordOutput(20, synthetic = true)
        state.onFrameSubmitted(20, -1, 110)
        state.recordOutput(30, synthetic = true)
        state.onFrameSubmitted(30, 130, 120)
        state.onFrameSubmitted(40, 140, 130) // Unknown media timestamp.
        state.onFrameSubmitted(30, 131, 121) // Duplicate callback for an already consumed output.
        state.onFrameSubmitted(10, 150, 140) // A later valid release cannot resurrect the old output.
        state.recordOutput(50, synthetic = false)
        state.onFrameSubmitted(50, 160, 0) // Callback time is diagnostic data, not an eligibility gate.

        assertArrayEquals(
            longArrayOf(0, 100, 90, 30, 130, 120, 50, 160, 0),
            state.snapshot().getLongArray("releaseTrace"),
        )
        assertEquals(5L, state.emitted.get())
        assertEquals(5L, state.presented.get())
        assertEquals(3L, state.generated.get())
        assertEquals(3L, state.submitted.get())
    }

    @Test
    fun seekResetDropsPendingCallbacksAndOldGenerationCannotRefillTrace() {
        val state = InterpolationState().apply { setEnabled(true) }
        val oldGeneration = state.generation.get()
        state.recordOutput(100, synthetic = true, expectedGeneration = oldGeneration)
        state.onFrameSubmitted(100, 10_000, 9000)
        state.recordOutput(200, synthetic = true, expectedGeneration = oldGeneration)
        state.reset()

        state.onFrameSubmitted(100, 20_000, 19_000)
        state.onFrameSubmitted(200, 30_000, 29_000)
        state.recordOutput(300, synthetic = true, expectedGeneration = oldGeneration)
        state.onFrameSubmitted(300, 40_000, 39_000)
        assertArrayEquals(longArrayOf(), state.snapshot().getLongArray("releaseTrace"))
        assertEquals(0L, state.emitted.get())
        assertEquals(0L, state.presented.get())
        assertEquals(0L, state.generated.get())
        assertEquals(0L, state.submitted.get())

        state.recordOutput(400, synthetic = false, expectedGeneration = state.generation.get())
        state.onFrameSubmitted(400, 50_000, 49_000)
        assertArrayEquals(longArrayOf(400, 50_000, 49_000), state.snapshot().getLongArray("releaseTrace"))
        assertEquals(1L, state.emitted.get())
        assertEquals(1L, state.presented.get())
        assertEquals(0L, state.submitted.get())
    }

    @Test
    fun snapshotArraysAreDetachedFromInternalTraceAndLaterSnapshots() {
        val state = InterpolationState().apply { setEnabled(true) }
        state.recordOutput(100, synthetic = true)
        state.onFrameSubmitted(100, 1000, 900)
        val firstBundle = state.snapshot()
        val firstArray = checkNotNull(firstBundle.getLongArray("releaseTrace"))
        firstArray.fill(-1)

        val secondArray = checkNotNull(state.snapshot().getLongArray("releaseTrace"))
        assertNotSame(firstArray, secondArray)
        assertArrayEquals(longArrayOf(100, 1000, 900), secondArray)
        state.recordOutput(200, synthetic = false)
        state.onFrameSubmitted(200, 2000, 1900)
        assertArrayEquals("A prior snapshot does not grow when new callbacks arrive", longArrayOf(100, 1000, 900), secondArray)
        secondArray[1] = Long.MAX_VALUE
        firstBundle.remove("releaseTrace")

        assertArrayEquals(
            longArrayOf(100, 1000, 900, 200, 2000, 1900),
            state.snapshot().getLongArray("releaseTrace"),
        )
        assertEquals(2L, state.presented.get())
        assertEquals(1L, state.submitted.get())
    }
}
