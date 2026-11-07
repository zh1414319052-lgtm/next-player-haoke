package dev.anilbeesetti.nextplayer.feature.player.buttons

import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerButtonInputTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun touchAndAccessibilityClicksEachInvokeExactlyOnce() {
        var clicks = 0
        rule.setContent { NextPlayerTheme { PlayerButton(Modifier.testTag("button"), onClick = { clicks++ }) { Text("Action") } } }
        rule.onNodeWithTag("button").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, clicks) }
        rule.onNodeWithTag("button").performClick()
        rule.runOnIdle { assertEquals(2, clicks) }
    }

    @Test fun nativeDpadCenterActivatesTheFocusedButtonExactlyOnce() {
        var clicks = 0
        val focus = FocusRequester()
        lateinit var owner: View
        rule.setContent {
            owner = LocalView.current
            NextPlayerTheme { PlayerButton(Modifier.focusRequester(focus), onClick = { clicks++ }) { Text("Action") } }
        }
        rule.runOnUiThread {
            owner.requestFocus()
            focus.requestFocus()
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun recompositionUsesTheLatestClickCallbackAndDisabledButtonDoesNothing() {
        val updated = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var oldClicks = 0
        var newClicks = 0
        rule.setContent {
            val callback: () -> Unit = if (updated.value) { { newClicks++ } } else { { oldClicks++ } }
            NextPlayerTheme { PlayerButton(Modifier.testTag("button"), enabled.value, callback) { Text("Action") } }
        }
        rule.runOnIdle { updated.value = true }
        rule.onNodeWithTag("button").performClick()
        rule.runOnIdle { assertEquals(0, oldClicks); assertEquals(1, newClicks); enabled.value = false }
        rule.onNodeWithTag("button").assertIsNotEnabled().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, newClicks) }
    }

    @Test fun longPressInvokesOnlyTheLongPressAction() {
        var clicks = 0
        var longClicks = 0
        rule.setContent {
            NextPlayerTheme { PlayerButton(Modifier.testTag("button"), onClick = { clicks++ }, onLongClick = { longClicks++ }) { Text("Action") } }
        }
        rule.onNodeWithTag("button").performTouchInput { longClick() }
        rule.runOnIdle { assertEquals(0, clicks); assertEquals(1, longClicks) }
    }
}
