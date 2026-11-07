@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer.feature.player

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.model.DoubleTapGesture
import dev.anilbeesetti.nextplayer.core.model.VideoContentScale
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberSeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberTapGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVideoZoomAndContentScaleState
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "port")
class PlayerRemoteInputTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var controls: ControlsVisibilityState

    @Test
    fun phoneAcceptsPlaybackAndDpadKeysAfterControlsDisappear() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showPlayer(player)
            composeRule.mainClock.autoAdvance = false
            hideControls()
            key(Key.DirectionRight)
            composeRule.runOnIdle {
                assertEquals(30_000L, player.currentPosition)
                assertFalse(controls.controlsVisible)
            }
            key(Key.MediaPlayPause)
            composeRule.runOnIdle { assertTrue(player.playWhenReady) }
            hideControls()
            key(Key.DirectionCenter)
            composeRule.runOnIdle { assertTrue(controls.controlsVisible) }
            composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.play_pause)).assertIsFocused()
            key(Key.DirectionCenter)
            composeRule.runOnIdle { assertFalse(player.playWhenReady) }
        } finally {
            composeRule.runOnUiThread { player.pause(); player.release() }
        }
    }

    @Test
    fun consumedKeyPressOwnsItsReleaseAndRepeatsUntilTheNextPress() {
        val player = composeRule.runOnIdle { TestPlayer() }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        try {
            composeRule.runOnIdle {
                val visibility = ControlsVisibilityState(player, 4.seconds, scope)
                val input = dev.anilbeesetti.nextplayer.feature.player.state.PlayerKeyInputState(player, visibility, 10_000, scope)
                fun event(action: Int, code: Int) = androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(action, code))
                val down = android.view.KeyEvent.ACTION_DOWN
                val up = android.view.KeyEvent.ACTION_UP
                val center = android.view.KeyEvent.KEYCODE_DPAD_CENTER
                visibility.hideControls()
                assertTrue(input.onKeyEvent(event(down, center)))
                assertTrue("A held reveal key cannot activate the newly visible button", input.onKeyEvent(event(down, center)))
                assertTrue("The reveal release belongs to its consumed press", input.onKeyEvent(event(up, center)))
                assertFalse("A fresh press can activate the focused control", input.onKeyEvent(event(down, center)))
                assertFalse(input.onKeyEvent(event(up, center)))
                visibility.lockControls()
                visibility.hideControls()
                assertTrue(input.onKeyEvent(event(down, center)))
                assertTrue(input.onKeyEvent(event(up, center)))
                assertTrue(visibility.controlsLocked)
                assertTrue(input.onKeyEvent(event(down, center)))
                assertTrue(input.onKeyEvent(event(up, center)))
                assertFalse(visibility.controlsLocked)
                input.cancelPendingReset()
            }
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            composeRule.runOnUiThread { player.pause(); player.release() }
        }
    }

    @Test
    fun lockConsumesTransportKeysAndRemoteCanRevealThenUnlock() {
        val player = composeRule.runOnIdle { TestPlayer() }
        try {
            showPlayer(player)
            composeRule.runOnIdle { controls.lockControls(); controls.hideControls() }
            composeRule.waitForIdle()
            key(Key.MediaPlayPause)
            composeRule.runOnIdle { assertFalse(player.playWhenReady); assertTrue(controls.controlsLocked) }
            hideControls()
            key(Key.DirectionCenter)
            composeRule.runOnIdle { assertTrue(controls.controlsVisible); assertTrue(controls.controlsLocked) }
            key(Key.DirectionCenter)
            composeRule.runOnIdle { assertFalse(controls.controlsLocked) }
        } finally {
            composeRule.runOnUiThread { player.pause(); player.release() }
        }
    }

    @Test
    fun castRemoteCommandsNeverResumeOrSeekTheLocalPlayer() {
        val player = composeRule.runOnIdle { TestPlayer() }
        val actions = mutableListOf<PlayerAction>()
        val cast = dev.anilbeesetti.nextplayer.core.cast.model.CastState(
            device = dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice("QA", "qa", "http://127.0.0.1", "http://127.0.0.1/control", renderingControlUrl = null),
            positionMs = 20_000,
            durationMs = 60_000,
            transportState = dev.anilbeesetti.nextplayer.core.cast.model.CastTransportState.PLAYING,
        )
        try {
            showPlayer(player, PlayerUiState(castState = cast), actions::add)
            hideControls()
            key(Key.DirectionRight)
            key(Key.MediaPlayPause)
            composeRule.runOnIdle {
                assertTrue(actions.contains(PlayerAction.CastSeekTo(30_000)))
                assertTrue(actions.contains(PlayerAction.CastTogglePlayPause))
                assertEquals(20_000L, player.currentPosition)
                assertFalse(player.playWhenReady)
            }
        } finally {
            composeRule.runOnUiThread { player.pause(); player.release() }
        }
    }

    private fun showPlayer(player: Player, state: PlayerUiState = PlayerUiState(), onAction: (PlayerAction) -> Unit = {}) {
        composeRule.setContent {
            controls = rememberControlsVisibilityState(player, 4.seconds)
            NextPlayerTheme {
                MediaPlayerControls(
                    viewModel = null,
                    player = player,
                    state = state,
                    onAction = onAction,
                    controlsVisibilityState = controls,
                    tapGestureState = rememberTapGestureState(player, DoubleTapGesture.FAST_FORWARD_AND_REWIND, 10_000L, false, 2f),
                    seekGestureState = rememberSeekGestureState(player, 1f, false),
                    videoZoomAndContentScaleState = rememberVideoZoomAndContentScaleState(player, VideoContentScale.BEST_FIT, false, false, {}),
                    isPipSupported = false,
                    onPictureInPictureClick = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun hideControls() {
        composeRule.runOnIdle { controls.hideControls() }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
    }

    private fun key(key: Key) {
        composeRule.onRoot().performKeyInput { pressKey(key) }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
    }

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var state = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build())
            .setPlaylist(listOf(MediaItemData.Builder("video").setDurationUs(60_000_000).setIsSeekable(true).build()))
            .setContentPositionMs(20_000)
            .setPlaybackState(Player.STATE_READY)
            .build()

        override fun getState(): State = state
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            state = state.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            return Futures.immediateVoidFuture()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            state = state.buildUpon().setContentPositionMs(positionMs).build()
            return Futures.immediateVoidFuture()
        }
    }
}
