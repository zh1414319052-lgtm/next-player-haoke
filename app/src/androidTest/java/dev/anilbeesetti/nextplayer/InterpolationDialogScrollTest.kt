package dev.anilbeesetti.nextplayer

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationDialog
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class InterpolationDialogScrollTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun constrainedDialogScrollsToStatusAndDisplayRateThenDismisses() {
        val visible = mutableStateOf(true)
        var dismissed = 0
        composeRule.setContent {
            NextPlayerTheme {
                if (visible.value) {
                    InterpolationDialog(player = null) {
                        visible.value = false
                        dismissed++
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Limit this test dialog's own window, not the Activity, system display or orientation.
        // A parent Box cannot constrain AlertDialog because it owns a separate Android window.
        composeRule.runOnUiThread {
            val dialogs = WindowInspector.getGlobalWindowViews().mapNotNull(::findDialog)
            assertEquals("The isolated host must own exactly one dialog", 1, dialogs.size)
            val density = composeRule.activity.resources.displayMetrics.density
            dialogs.single().window.setLayout((320 * density).roundToInt(), (340 * density).roundToInt())
        }
        composeRule.waitForIdle()

        val scroll = composeRule.onNode(hasScrollAction())
        val initialAxis = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val beforeTouch = initialAxis.value()
        assertTrue("The reduced-height fixture must actually overflow", initialAxis.maxValue() > 0f)
        val displayRate = composeRule.onNode(hasText("Hz", substring = true))
        displayRate.assertExists().assertIsNotDisplayed()

        scroll.performTouchInput { swipeUp(durationMillis = 500) }
        composeRule.waitForIdle()
        val afterTouch = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        Log.i("NextInterpolationScroll", "beforeTouch=$beforeTouch afterTouch=$afterTouch max=${initialAxis.maxValue()}")

        // Also exercise accessibility scrolling so a failed touch injection can be distinguished
        // from a layout that cannot bring the bottom status into view at all.
        val waiting = composeRule.activity.getString(coreUiR.string.interpolation_waiting)
        composeRule.onNodeWithText(waiting).performScrollTo().assertIsDisplayed()
        displayRate.performScrollTo().assertIsDisplayed()
        Log.i("NextInterpolationScroll", "Status and display Hz became visible through scroll semantics")

        val done = composeRule.activity.getString(coreUiR.string.interpolation_done)
        composeRule.onNodeWithText(done).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(coreUiR.string.interpolation_title)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
        assertTrue("Compose touch injection did not advance the scroll position: $beforeTouch -> $afterTouch", afterTouch > beforeTouch)
    }

    private fun findDialog(view: View): DialogWindowProvider? {
        if (view is DialogWindowProvider) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findDialog(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
