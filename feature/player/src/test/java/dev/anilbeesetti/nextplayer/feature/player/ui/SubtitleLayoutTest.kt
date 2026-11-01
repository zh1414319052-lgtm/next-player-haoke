package dev.anilbeesetti.nextplayer.feature.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLayoutTest {
    @Test fun defaultPositionClearsMeasuredControlsInBothOrientations() {
        for (height in listOf(1080, 2376)) {
            val reserved = subtitleBottomPaddingFraction(height, 360, false)
            assertTrue(reserved * height >= 359.5f)
        }
    }

    @Test fun hiddenControlsAndPictureInPictureKeepTheNormalBaseline() {
        assertEquals(0.08f, subtitleBottomPaddingFraction(1080, 0, false), 0.0001f)
        assertEquals(0.08f, subtitleBottomPaddingFraction(300, 360, true), 0.0001f)
    }

    @Test fun unmeasuredAndSmallViewportsKeepCaptionSpaceBounded() {
        assertEquals(0.08f, subtitleBottomPaddingFraction(0, 360, false), 0.0001f)
        assertEquals(0.08f, subtitleBottomPaddingFraction(1080, -1, false), 0.0001f)
        assertEquals(0.5f, subtitleBottomPaddingFraction(300, 360, false), 0.0001f)
    }
}
