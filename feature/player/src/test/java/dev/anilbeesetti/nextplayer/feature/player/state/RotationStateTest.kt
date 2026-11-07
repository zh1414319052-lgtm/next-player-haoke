package dev.anilbeesetti.nextplayer.feature.player.state

import android.app.Activity
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "port")
class RotationStateTest {
    @Test
    fun repeatedClicksToggleTheRequestedTargetBeforeConfigurationCatchesUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        repeat(6) { index ->
            RotationState(activity).rotate()
            assertEquals(
                if (index % 2 == 0) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                activity.requestedOrientation,
            )
        }
    }

    @Test
    fun reversedLandscapeAndPortraitPoliciesAlsoRoundTrip() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
            activity.requestedOrientation = orientation
            RotationState(activity).rotate()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        RotationState(activity).rotate()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
    }
}
