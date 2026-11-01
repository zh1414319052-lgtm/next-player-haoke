package dev.anilbeesetti.nextplayer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.core.ui.composables.PermissionMissingView
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ContinueWatchingCard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CinemaRedesignDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mediaAndNetworkPermissionPromptsRequireAnExplicitActionAndKeepContentGated() {
        val permission = mutableStateOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        val granted = mutableStateOf(false)
        var requests = 0
        compose.setContent {
            NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    PermissionMissingView(
                        isGranted = granted.value,
                        showRationale = false,
                        permission = permission.value,
                        launchPermissionRequest = { requests++ },
                    ) { Text("protected synthetic content") }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithText("protected synthetic content").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.grant_permission)).performClick()
        compose.runOnIdle { assertEquals(1, requests); requests = 0; permission.value = "android.permission.ACCESS_LOCAL_NETWORK" }
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithText("protected synthetic content").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.grant_permission)).performClick()
        compose.runOnIdle { assertEquals(1, requests); granted.value = true }
        compose.onNodeWithText("protected synthetic content").assertIsDisplayed()
    }

    @Test fun resumeCardUsesTheActualPositionFractionAndClampsCompletedVideos() {
        val video = Video.sample.copy(
            nameWithExtension = "山野之旅.mkv", duration = 2_852_000, playbackPosition = 1_122_000,
            formattedDuration = "47:32", uriString = "file:///nonexistent-cinema-synthetic-video.mp4",
        )
        val current = mutableStateOf(video)
        compose.setContent {
            NextPlayerTheme(darkTheme = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp)) { ContinueWatchingCard(video = current.value, onClick = {}) }
                }
            }
        }
        compose.waitForIdle()
        val track = compose.onNodeWithTag("continue_progress_track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val fill = compose.onNodeWithTag("continue_progress_fill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(1_122_000f / 2_852_000f, fill.width / track.width, 0.01f)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "cinema-resume-card.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.runOnIdle { current.value = video.copy(playbackPosition = video.duration + 10_000) }
        val completed = compose.onNodeWithTag("continue_progress_fill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(track.width, completed.width, 1f)
    }
}
