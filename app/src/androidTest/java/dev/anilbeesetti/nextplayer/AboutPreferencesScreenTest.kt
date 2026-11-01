package dev.anilbeesetti.nextplayer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.LocalManualUpdateCheck
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.settings.screens.about.AboutPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.screens.about.AboutPreferencesViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutPreferencesScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aboutShowsLicensesWebsiteAndUpdatesWithoutGitHubOrDonationNodes() {
        setAboutContent(manualUpdateCheck = {})

        listOf(R.string.github, R.string.donate, R.string.kofi, R.string.paypal, R.string.upi).forEach { id ->
            composeRule.onAllNodes(
                hasText(text(id), ignoreCase = true) or hasContentDescription(text(id), ignoreCase = true),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        }
        preference(R.string.open_source_licenses).performScrollTo().assertIsDisplayed()
        preference(R.string.haoke_tools).performScrollTo().assertIsDisplayed()
        preference(R.string.updates_check).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aboutActionsInvokeTheirCallbacksAndCaptureTheWebsiteUri() {
        var librariesOpened = 0
        var updateChecks = 0
        var navigationsUp = 0
        val openedUris = mutableListOf<String>()
        setAboutContent(
            manualUpdateCheck = { updateChecks++ },
            navigateUp = { navigationsUp++ },
            openLibraries = { librariesOpened++ },
            openedUris = openedUris,
        )

        preference(R.string.open_source_licenses).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, librariesOpened) }

        preference(R.string.updates_check).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, updateChecks) }

        preference(R.string.haoke_tools).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf("https://haoke.org"), openedUris) }

        composeRule.onNodeWithContentDescription(text(R.string.navigate_up)).performClick()
        composeRule.runOnIdle { assertEquals(1, navigationsUp) }
    }

    @Test
    fun missingManualUpdateCallbackHidesOnlyTheUpdatePreference() {
        setAboutContent(manualUpdateCheck = null)

        preference(R.string.open_source_licenses).performScrollTo().assertIsDisplayed()
        preference(R.string.haoke_tools).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.updates_check)).assertDoesNotExist()
    }

    private fun setAboutContent(
        manualUpdateCheck: (() -> Unit)?,
        navigateUp: () -> Unit = {},
        openLibraries: () -> Unit = {},
        openedUris: MutableList<String> = mutableListOf(),
    ) {
        val viewModel = AboutPreferencesViewModel(
            context = composeRule.activity,
            output = AboutPreferencesViewModel.Output(
                navigateUp = navigateUp,
                openLibraries = openLibraries,
            ),
        )
        // Capture links without opening a browser or making a network request.
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalManualUpdateCheck provides manualUpdateCheck,
                LocalUriHandler provides uriHandler,
            ) {
                NextPlayerTheme {
                    AboutPreferencesScreen(viewModel = viewModel)
                }
            }
        }
    }

    // A section heading can share the preference title, so select only the actionable row.
    private fun preference(id: Int) = composeRule.onNode(hasText(text(id)) and hasClickAction())

    private fun text(id: Int) = composeRule.activity.getString(id)
}
