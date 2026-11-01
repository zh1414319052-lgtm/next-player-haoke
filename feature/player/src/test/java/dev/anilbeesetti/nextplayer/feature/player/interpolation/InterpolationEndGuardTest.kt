package dev.anilbeesetti.nextplayer.feature.player.interpolation

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpolationEndGuardTest {
    private fun guard(durationMs: Long, positionMs: Long, hasNext: Boolean = false, repeatMode: Int = Player.REPEAT_MODE_OFF) =
        InterpolationEndGuard.isEndingSoon(durationMs, positionMs, hasNext, repeatMode)

    @Test fun finalItemGuardsItsLastSecondAndAHalf() {
        assertTrue(guard(durationMs = 47_000, positionMs = 45_500))
        assertTrue(guard(durationMs = 47_000, positionMs = 46_990))
        // Playback can report a position beyond the duration while it waits for the end.
        assertTrue(guard(durationMs = 47_000, positionMs = 60_000))
        assertFalse(guard(durationMs = 47_000, positionMs = 45_499))
        assertFalse(guard(durationMs = 47_000, positionMs = 0))
    }

    @Test fun shortFinalItemIsGuardedFromTheStart() {
        assertTrue(guard(durationMs = 1_200, positionMs = 0))
    }

    @Test fun itemsThatContinueIntoAnotherItemAreNotGuarded() {
        assertFalse(guard(durationMs = 47_000, positionMs = 46_000, hasNext = true))
        assertFalse(guard(durationMs = 47_000, positionMs = 46_000, repeatMode = Player.REPEAT_MODE_ONE))
        assertFalse(guard(durationMs = 47_000, positionMs = 46_000, repeatMode = Player.REPEAT_MODE_ALL))
    }

    @Test fun unknownDurationIsNotGuarded() {
        assertFalse(guard(durationMs = C.TIME_UNSET, positionMs = 0))
        assertFalse(guard(durationMs = 0, positionMs = 0))
    }
}
