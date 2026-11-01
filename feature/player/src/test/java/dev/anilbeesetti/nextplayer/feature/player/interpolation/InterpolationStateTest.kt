package dev.anilbeesetti.nextplayer.feature.player.interpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InterpolationStateTest {
    @Test fun oldGpuWorkCannotRepopulateCountersAfterToggleOrSeek() {
        val state = InterpolationState()
        state.setEnabled(true)
        val oldGeneration = state.generation.get()
        state.recordOutput(10_000, true, oldGeneration)
        state.setEnabled(false)
        state.recordOutput(20_000, true, oldGeneration)
        state.onFrameSubmitted(10_000)
        state.onFrameSubmitted(20_000)
        assertEquals(0, state.generated.get())
        assertEquals(0, state.presented.get())
        state.setEnabled(true)
        state.recordOutput(30_000, true, state.generation.get())
        state.onFrameSubmitted(30_000)
        assertEquals(1, state.generated.get())
        assertEquals(1, state.submitted.get())
        state.reset()
        state.recordOutput(40_000, true, oldGeneration)
        assertEquals(0, state.emitted.get())
    }

    @Test fun changingBetweenSixtyAndDoubleResetsPendingWork() {
        val state = InterpolationState()
        state.setEnabled(true)
        state.setTargetFps(60)
        val oldGeneration = state.generation.get()
        state.recordOutput(16_666, true, oldGeneration)
        state.fail("overload")
        state.setTargetFps(0)
        assertEquals(0, state.targetFps)
        assertEquals("", state.failure)
        assertFalse(state.active)
        assertTrue(state.canInterpolate())
        state.recordOutput(33_333, true, oldGeneration)
        state.onFrameSubmitted(16_666)
        assertEquals(0L, state.generated.get())
        assertEquals(0L, state.presented.get())
        state.recordOutput(44_444, true, state.generation.get())
        state.onFrameSubmitted(44_444)
        assertEquals(1L, state.submitted.get())
        state.setTargetFps(60)
        assertEquals(60, state.targetFps)
        assertEquals(0L, state.submitted.get())
    }

    @Test fun endingSoonPassesFramesThroughWithoutChangingTheSetting() {
        val state = InterpolationState()
        state.setEnabled(true)
        state.setTargetFps(60)
        assertTrue(state.interpolatesNow())
        state.endingSoon = true
        assertFalse(state.interpolatesNow())
        // The user's choice and the display frame-rate strategy keep following canInterpolate().
        assertTrue(state.enabled)
        assertTrue(state.canInterpolate())
        state.endingSoon = false
        assertTrue(state.interpolatesNow())
        state.setEnabled(false)
        assertFalse(state.interpolatesNow())
    }

    @Test fun savedNinetyAndUnknownLegacyValuesFallBackToSixty() {
        for (target in listOf(0, 60)) {
            assertTrue(InterpolationState.isSupportedTargetFps(target))
            assertEquals(target, InterpolationState.restoreTargetFps(target))
        }
        for (target in listOf(-1, 24, 90, 120, Int.MAX_VALUE)) {
            assertFalse(InterpolationState.isSupportedTargetFps(target))
            assertEquals(60, InterpolationState.restoreTargetFps(target))
        }
    }

    @Test fun unsupportedCommandTargetsAreRejectedWithoutResettingCurrentPlayback() {
        val state = InterpolationState()
        state.setEnabled(true)
        state.setTargetFps(60)
        state.active = true
        state.recordOutput(16_666, true)
        val generation = state.generation.get()
        for (target in listOf(-1, 24, 90, 120, Int.MAX_VALUE)) {
            try {
                state.setTargetFps(target)
                fail("Unsupported target $target must be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals(60, state.targetFps)
                assertTrue(state.enabled)
                assertTrue(state.active)
                assertEquals(generation, state.generation.get())
                assertEquals(1L, state.generated.get())
            }
        }
        state.onFrameSubmitted(16_666)
        assertEquals(1L, state.submitted.get())
    }
}
