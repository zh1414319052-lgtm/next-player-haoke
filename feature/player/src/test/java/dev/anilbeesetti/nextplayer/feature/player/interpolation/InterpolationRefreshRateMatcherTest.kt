package dev.anilbeesetti.nextplayer.feature.player.interpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpolationRefreshRateMatcherTest {
    private val modes = listOf(
        InterpolationDisplayMode(1, 1264, 2780, 120f),
        InterpolationDisplayMode(2, 1264, 2780, 60f),
        InterpolationDisplayMode(3, 1264, 2780, 90f),
    )

    @Test fun matchesSixtyWithoutNeedlessHighRefresh() {
        assertEquals(2, interpolationDisplayMode(60f, 1264, 2780, modes))
    }

    @Test fun usesAnIntegerMultipleWhenThereIsNoExactMode() {
        assertEquals(1, interpolationDisplayMode(60f, 1264, 2780, modes.filter { it.id != 2 }))
        assertEquals(1, interpolationDisplayMode(24f, 1264, 2780, modes))
    }

    @Test fun handlesFractionalCadenceAndDoubleRateWithoutChangingResolution() {
        assertEquals(2, interpolationDisplayMode(59.94f, 1264, 2780, modes))
        assertEquals(1, interpolationDisplayMode(47.952f, 1264, 2780, modes))
        val withOtherResolution = modes + InterpolationDisplayMode(4, 1080, 2400, 96f)
        assertEquals(1, interpolationDisplayMode(48f, 1264, 2780, withOtherResolution))
    }

    @Test fun lowerRefreshOnlyDeviceRemainsUsable() {
        assertEquals(2, interpolationDisplayMode(48f, 1264, 2780, modes.filter { it.id == 2 }))
    }

    @Test fun invalidOrUnmatchedModesDoNotRequestAMode() {
        assertNull(interpolationDisplayMode(0f, 1264, 2780, modes))
        assertNull(interpolationDisplayMode(Float.NaN, 1264, 2780, modes))
        assertNull(interpolationDisplayMode(60f, 1920, 1080, modes))
    }

    @Test fun onlyActivePlayingInterpolationRequestsHighRefresh() {
        assertEquals(60f, interpolationDisplayFps(true, "active", 60, 24f, true), 0f)
        assertEquals(47.952f, interpolationDisplayFps(true, "active", 0, 23.976f, true), 0.001f)
        assertEquals(0f, interpolationDisplayFps(false, "active", 60, 24f, true), 0f)
        assertEquals(0f, interpolationDisplayFps(true, "active", 60, 24f, false), 0f)
        for (status in listOf("off", "waiting", "speed", "overload", "resolution", "framerate", "failed", "decoder", "tunneling")) {
            assertEquals(status, 0f, interpolationDisplayFps(true, status, 60, 24f, true), 0f)
        }
    }

    @Test fun invalidTargetsAndSourceRatesDoNotRequestHighRefresh() {
        for (target in listOf(-1, 24, 90, 120, Int.MAX_VALUE)) {
            assertEquals(0f, interpolationDisplayFps(true, "active", target, 24f, true), 0f)
        }
        assertEquals(0f, interpolationDisplayFps(true, "active", 0, Float.NaN, true), 0f)
        assertEquals(0f, interpolationDisplayFps(true, "active", 0, 0f, true), 0f)
        assertEquals(0f, interpolationDisplayFps(true, "active", 0, 60f, true), 0f)
    }
}
