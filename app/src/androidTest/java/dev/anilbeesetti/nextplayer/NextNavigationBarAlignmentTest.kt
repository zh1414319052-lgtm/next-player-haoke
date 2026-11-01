package dev.anilbeesetti.nextplayer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabState
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.navigation.NextNavigationBar
import dev.anilbeesetti.nextplayer.navigation.TopLevelDestination
import dev.anilbeesetti.nextplayer.navigation.rememberTopLevelNavState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextNavigationBarAlignmentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyPageFabAlignsWithFourthTabAcrossWidthsAndLayoutDirections() {
        var width by mutableStateOf(360.dp)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                    val state = rememberTopLevelNavState()
                    Box(Modifier.requiredWidth(width)) {
                        NextNavigationBar(
                            state = state,
                            fabState = TopLevelFabState(
                                icon = when (state.topLevelRoute) {
                                    TopLevelDestination.MEDIA.route -> NextIcons.Play
                                    TopLevelDestination.MORE.route -> NextIcons.Settings
                                    else -> NextIcons.Add
                                },
                                onClick = {},
                            ),
                            contentFocusRequester = remember { FocusRequester() },
                            fabFocusRequester = remember { FocusRequester() },
                        )
                    }
                }
            }
        }

        val tabMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val tabs = composeRule.onAllNodes(tabMatcher)
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (widthDp in listOf(320, 347, 360)) {
                composeRule.runOnIdle {
                    width = widthDp.dp
                    direction = layoutDirection
                }
                composeRule.waitForIdle()
                tabs.assertCountEquals(4)
                for (index in TopLevelDestination.entries.indices) {
                    val destination = TopLevelDestination.entries[index]
                    val selectedTabNode = composeRule.onNode(
                        tabMatcher and hasText(composeRule.activity.getString(destination.labelRes)),
                    )
                    selectedTabNode.performClick()
                    composeRule.waitForIdle()
                    val selectedTab = selectedTabNode.fetchSemanticsNode().boundsInRoot
                    val selectedBackground = composeRule.onNodeWithTag(
                        "top_level_selected_background",
                        useUnmergedTree = true,
                    ).fetchSemanticsNode().boundsInRoot
                    val fourthTab = composeRule.onNode(
                        tabMatcher and hasText(composeRule.activity.getString(TopLevelDestination.MORE.labelRes)),
                    ).fetchSemanticsNode().boundsInRoot
                    val fab = composeRule.onNodeWithTag("top_level_fab").fetchSemanticsNode().boundsInRoot
                    assertEquals(
                        "FAB for tab $index must align with tab 4 ($layoutDirection, ${widthDp}dp)",
                        fourthTab.center.x,
                        fab.center.x,
                        1f,
                    )
                    assertEquals("Selected background width must match FAB", fab.width, selectedBackground.width, 1f)
                    assertEquals("Selected background height must match FAB", fab.height, selectedBackground.height, 1f)
                    assertEquals("Selected background must stay centered on its tab", selectedTab.center.x, selectedBackground.center.x, 1f)
                }
            }
        }
    }
}
