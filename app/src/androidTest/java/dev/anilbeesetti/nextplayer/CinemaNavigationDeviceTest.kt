package dev.anilbeesetti.nextplayer

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import org.koin.core.context.GlobalContext
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real application routing; no media permission or personal library content is needed. */
@RunWith(AndroidJUnit4::class)
class CinemaNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realNavigationSettingsAndCancelSurviveRecreation() {
        fun text(id: Int) = compose.activity.getString(id)
        fun tab(id: Int) = compose.onNode(hasContentDescription(text(id)) and hasClickAction())
        fun preference(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())
        // OEM launch/permission windows can precede the application's Compose hierarchy.
        // Wait for the actual app window; the operator denies media access for this test.
        compose.waitUntil(90_000) {
            compose.activity.hasWindowFocus() && runCatching {
                compose.onAllNodes(hasContentDescription(text(R.string.more)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        tab(R.string.more).performClick()
        compose.onNodeWithText(text(R.string.vault)).assertIsDisplayed()
        compose.onNodeWithTag("top_level_fab").assertDoesNotExist()
        save("cinema-more")
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        compose.onNodeWithText(text(R.string.cinema_settings_playback)).assertIsDisplayed()
        save("cinema-settings")
        preference(R.string.player_name).performClick()
        val preferences = GlobalContext.get().get<PreferencesRepository>()
        preference(R.string.default_playback_speed).performClick()
        compose.onNodeWithText(text(R.string.cinema_autosave)).assertIsDisplayed()
        compose.onNode(hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.waitUntil(10_000) { preferences.playerPreferences.value.defaultPlaybackSpeed == 1.5f }
        save("cinema-default-speed")
        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.onNodeWithText("1.5×").assertIsDisplayed()
        preference(R.string.default_playback_speed).performClick()
        compose.onNode(hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitUntil(10_000) { preferences.playerPreferences.value.defaultPlaybackSpeed == 1f }
        compose.onNodeWithText(text(R.string.done)).performClick()
        preference(R.string.custom_skip_durations).performScrollTo().assertIsDisplayed()
        save("cinema-player-settings")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(90_000) { compose.activity.hasWindowFocus() }
        preference(R.string.custom_skip_durations).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.custom_intro_seconds)).assertIsDisplayed()
        save("cinema-skip-dialog")
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithContentDescription(text(R.string.navigate_up)).performClick()
        compose.onNodeWithContentDescription(text(R.string.navigate_up)).performClick()
        tab(R.string.network).performClick()
        save("cinema-network")
        tab(R.string.playlists).performClick()
        save("cinema-playlists")
        tab(R.string.more).performClick()
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        preference(R.string.about_name).performScrollTo().performClick()
        preference(R.string.open_source_licenses).performScrollTo().assertIsDisplayed()
        save("cinema-about")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        // Compose can be idle while the platform dialog window is still fading.
        Thread.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
