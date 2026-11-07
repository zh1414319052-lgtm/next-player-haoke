package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.anilbeesetti.nextplayer.core.cast.model.CastState
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun rememberPlayerKeyInputState(
    player: Player,
    controls: ControlsVisibilityState,
    seekIncrementMs: Long,
): PlayerKeyInputState {
    val coroutineScope = rememberCoroutineScope()
    val state = remember(player, controls, seekIncrementMs) {
        PlayerKeyInputState(player, controls, seekIncrementMs, coroutineScope)
    }
    DisposableEffect(state) {
        onDispose { state.cancelPendingReset() }
    }
    return state
}

@Stable
internal class PlayerKeyInputState(
    private val player: Player,
    private val controls: ControlsVisibilityState,
    private val seekIncrementMs: Long,
    private val coroutineScope: CoroutineScope,
) {
    var isSeeking: Boolean by mutableStateOf(false)
        private set

    var seekOffsetMs: Long by mutableLongStateOf(0L)
        private set

    var seekPositionMs: Long by mutableLongStateOf(0L)
        private set

    private var resetJob: Job? = null
    private val consumedKeys = mutableSetOf<Key>()

    @OptIn(UnstableApi::class)
    fun onKeyEvent(
        keyEvent: KeyEvent,
        castState: CastState? = null,
        onCastPlayPause: () -> Unit = {},
        onCastSeek: (Long) -> Unit = {},
    ): Boolean {
        if (keyEvent.key == Key.Back && !controls.controlsLocked) {
            if (!controls.controlsVisible) return false // controls already hidden: let BACK exit
            if (keyEvent.type == KeyEventType.KeyUp) controls.hideControls()
            return true
        }
        if (keyEvent.type == KeyEventType.KeyUp) return consumedKeys.remove(keyEvent.key)
        if (keyEvent.type != KeyEventType.KeyDown) return false
        val alreadyConsumed = keyEvent.key in consumedKeys
        // Focus may move between down and up. A consumed press must never release
        // into a newly visible button, nor activate it through a held-key repeat.
        if (alreadyConsumed && keyEvent.key !in setOf(Key.DirectionLeft, Key.DirectionRight, Key.MediaFastForward, Key.MediaRewind)) return true
        val consumed = onKeyDown(keyEvent, castState, onCastPlayPause, onCastSeek)
        if (consumed) consumedKeys += keyEvent.key
        return consumed || alreadyConsumed
    }

    @OptIn(UnstableApi::class)
    private fun onKeyDown(
        keyEvent: KeyEvent,
        castState: CastState?,
        onCastPlayPause: () -> Unit,
        onCastSeek: (Long) -> Unit,
    ): Boolean {
        if (controls.controlsLocked) {
            return when (keyEvent.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (controls.controlsVisible) controls.unlockControls() else controls.showControls()
                    true
                }

                else -> {
                    controls.showControls()
                    keyEvent.key.isPlayerControlKey()
                }
            }
        }

        val casting = castState?.takeIf { it.isActive }
        fun seekBy(deltaMs: Long): Long {
            val duration = casting?.durationMs ?: player.duration
            val position = casting?.positionMs ?: player.currentPosition
            val target = (position + deltaMs).coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
            if (casting != null) onCastSeek(target) else player.seekTo(target)
            return target
        }

        fun togglePlayPause() {
            if (casting != null) onCastPlayPause() else if (player.playWhenReady) player.pause() else player.play()
        }

        return when (keyEvent.key) {
            Key.MediaPlayPause, Key.Spacebar -> {
                togglePlayPause()
                controls.showControls()
                true
            }

            Key.MediaPlay -> {
                if (casting == null) player.play() else if (!casting.isPlaying) onCastPlayPause()
                controls.showControls()
                true
            }

            Key.MediaPause -> {
                if (casting == null) player.pause() else if (casting.isPlaying) onCastPlayPause()
                controls.showControls()
                true
            }

            Key.MediaFastForward -> {
                seekBy(seekIncrementMs)
                controls.showControls()
                true
            }

            Key.MediaRewind -> {
                seekBy(-seekIncrementMs)
                controls.showControls()
                true
            }

            Key.MediaNext -> {
                if (casting == null) player.seekToNext()
                controls.showControls()
                true
            }

            Key.MediaPrevious -> {
                if (casting == null) player.seekToPrevious()
                controls.showControls()
                true
            }

            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                val controlsWereHidden = !controls.controlsVisible
                controls.showControls()
                controlsWereHidden
            }

            Key.DirectionLeft -> {
                if (!controls.controlsVisible) {
                    val position = seekBy(-seekIncrementMs)
                    showSeekFeedback(-seekIncrementMs, position)
                    true
                } else {
                    controls.showControls()
                    false
                }
            }

            Key.DirectionRight -> {
                if (!controls.controlsVisible) {
                    val position = seekBy(seekIncrementMs)
                    showSeekFeedback(seekIncrementMs, position)
                    true
                } else {
                    controls.showControls()
                    false
                }
            }

            Key.DirectionUp, Key.DirectionDown -> {
                if (!controls.controlsVisible) {
                    controls.showControls()
                    true
                } else {
                    controls.showControls()
                    false
                }
            }

            else -> false
        }
    }

    internal fun cancelPendingReset() {
        consumedKeys.clear()
        resetJob?.cancel()
        resetJob = null
    }

    private fun showSeekFeedback(deltaMs: Long, positionMs: Long) {
        if (!isSeeking) seekOffsetMs = 0L
        seekOffsetMs += deltaMs
        seekPositionMs = positionMs
        isSeeking = true
        resetJob?.cancel()
        resetJob = coroutineScope.launch {
            delay(1.seconds)
            isSeeking = false
        }
    }
}

internal fun Key.isPlayerControlKey(): Boolean = this in setOf(
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.MediaPlayPause, Key.Spacebar, Key.MediaPlay, Key.MediaPause,
    Key.MediaFastForward, Key.MediaRewind, Key.MediaNext, Key.MediaPrevious,
)
