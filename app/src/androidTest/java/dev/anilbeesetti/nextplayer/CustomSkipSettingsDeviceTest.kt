package dev.anilbeesetti.nextplayer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.settings.screens.player.PlayerPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.screens.player.PlayerPreferencesViewModel
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class CustomSkipSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualSettingsValidateSaveCancelAndRetainDurationsWhenDisabled() {
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        val original = preferences.playerPreferences.value
        val viewModel = PlayerPreferencesViewModel(preferences, PlayerPreferencesViewModel.Output(navigateUp = {}))
        fun text(id: Int) = compose.activity.getString(id)
        fun preference(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())
        try {
            compose.setContent { NextPlayerTheme(darkTheme = true, dynamicColor = false) { PlayerPreferencesScreen(viewModel) } }
            preference(R.string.custom_skip_durations).performScrollTo().performClick()
            val intro = compose.onNodeWithText(text(R.string.custom_intro_seconds))
            val outro = compose.onNodeWithText(text(R.string.custom_outro_seconds))
            intro.performTextReplacement("-1")
            compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
            intro.performTextReplacement("1801")
            compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
            intro.performTextReplacement("90")
            outro.performTextReplacement("60")
            Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.getExternalFilesDir(null), "custom-skip-settings.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
            compose.waitUntil(10_000) { preferences.playerPreferences.value.customIntroSeconds == 90 }
            assertEquals(60, preferences.playerPreferences.value.customOutroSeconds)
            preference(R.string.custom_skip_intro_outro).performScrollTo().performClick()
            compose.waitUntil(10_000) { preferences.playerPreferences.value.customSkipIntroOutro }
            assertTrue(preferences.playerPreferences.value.customSkipIntroOutro)
            preference(R.string.custom_skip_intro_outro).performClick()
            compose.waitUntil(10_000) { !preferences.playerPreferences.value.customSkipIntroOutro }
            assertFalse(preferences.playerPreferences.value.customSkipIntroOutro)
            preference(R.string.custom_skip_durations).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.custom_intro_seconds)).performTextReplacement("7")
            compose.onNodeWithText(text(R.string.cancel)).performClick()
            assertEquals(90, preferences.playerPreferences.value.customIntroSeconds)
            assertEquals(60, preferences.playerPreferences.value.customOutroSeconds)
        } finally { runBlocking { preferences.updatePlayerPreferences { original } } }
    }
}
