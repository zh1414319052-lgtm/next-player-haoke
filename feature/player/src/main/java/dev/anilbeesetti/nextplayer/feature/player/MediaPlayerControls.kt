package dev.anilbeesetti.nextplayer.feature.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.ui.components.requestFocusUntilLanded
import dev.anilbeesetti.nextplayer.core.ui.components.thenIf
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.SeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.TapGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.VideoZoomAndContentScaleState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberChaptersState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberMediaPresentationState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberMetadataState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberPlayerKeyInputState
import dev.anilbeesetti.nextplayer.feature.player.ui.DpadSeekIndicator
import dev.anilbeesetti.nextplayer.feature.player.ui.OverlayShowView
import dev.anilbeesetti.nextplayer.feature.player.ui.OverlayView
import dev.anilbeesetti.nextplayer.core.database.entities.DetectedSegmentEntity
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.service.undoIntroOutroSkip
import dev.anilbeesetti.nextplayer.feature.player.ui.PlayerGestureIndicators
import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import dev.anilbeesetti.nextplayer.feature.player.ui.CastDeviceSheet
import kotlinx.coroutines.flow.Flow
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.SkipUndoPill
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** Controls and overlays shared by live playback and the previewable content. */
@OptIn(UnstableApi::class)
@Composable
internal fun MediaPlayerControls(
    viewModel: PlayerViewModel?,
    player: Player,
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    controlsVisibilityState: ControlsVisibilityState,
    tapGestureState: TapGestureState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    isPipSupported: Boolean,
    onPictureInPictureClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBottomControlsHeightChanged: (Int) -> Unit = {},
) {
    val playerPreferences = state.playerPreferences
    val metadataState = rememberMetadataState(player)
    val mediaPresentationState = rememberMediaPresentationState(player)
    val progressState = rememberProgressStateWithTickInterval(player)
    val chaptersState = rememberChaptersState(player, progressState)
    val keyInputState = rememberPlayerKeyInputState(
        player = player,
        controls = controlsVisibilityState,
        seekIncrementMs = playerPreferences.seekIncrement.seconds.inWholeMilliseconds,
    )
    var overlayView by remember { mutableStateOf<OverlayView?>(null) }
    LaunchedEffect(metadataState.mediaId) {
        if (overlayView == OverlayView.CHAPTERS) overlayView = null
    }

    fun showOverlay(overlay: OverlayView) {
        controlsVisibilityState.hideControls()
        overlayView = overlay
    }

    val context = LocalContext.current
    val isTv = remember { context.isTelevision }
    val rootFocusRequester = remember { FocusRequester() }
    val middleControlsFocusRequester = remember { FocusRequester() }
    val unlockFocusRequester = remember { FocusRequester() }
    var isMiddleControlsFocused by remember { mutableStateOf(false) }
    var isUnlockFocused by remember { mutableStateOf(false) }
    if (isTv) {
        LaunchedEffect(controlsVisibilityState.controlsVisible, controlsVisibilityState.controlsLocked, overlayView) {
            if (overlayView != null) return@LaunchedEffect
            if (!controlsVisibilityState.controlsVisible) {
                runCatching { rootFocusRequester.requestFocus() }
                return@LaunchedEffect
            }
            val locked = controlsVisibilityState.controlsLocked
            val target = if (locked) unlockFocusRequester else middleControlsFocusRequester
            target.requestFocusUntilLanded(attempts = 20) { if (locked) isUnlockFocused else isMiddleControlsFocused }
        }
    }

    CompositionLocalProvider(
        LocalControlsVisibilityState provides controlsVisibilityState,
        LocalUseMaterialYouControls provides playerPreferences.useMaterialYouControls,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .thenIf(isTv) {
                    focusRequester(rootFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { keyEvent ->
                            if (overlayView != null) false else keyInputState.onKeyEvent(keyEvent)
                        }
                },
        ) {
            AnimatedVisibility(
                visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.62f),
                                0.28f to Color.Transparent,
                                0.65f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.72f),
                            ),
                        ),
                )
            }
            if (mediaPresentationState.isBuffering) {
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .size(72.dp),
                )
            }
            state.skipUndoKind?.takeIf { state.skipUndoToken > 0 && state.skipUndoExpireAt > System.currentTimeMillis() }?.let { skipKind ->
                val undoScope = rememberCoroutineScope()
                val labelRes = if (skipKind == DetectedSegmentEntity.KIND_INTRO) {
                    R.string.skip_intro
                } else {
                    R.string.skip_outro
                }
                SkipUndoPill(
                    label = stringResource(R.string.intro_outro_skipped_undo, stringResource(labelRes)),
                    undoText = stringResource(R.string.undo),
                    onUndo = {
                        undoScope.launch {
                            (player as? MediaController)?.undoIntroOutroSkip(state.skipUndoToken)
                            onAction(PlayerAction.UpdateSkipUndo(null, 0L, 0L))
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 132.dp),
                )
                LaunchedEffect(state.skipUndoExpireAt) {
                    val remaining = state.skipUndoExpireAt - System.currentTimeMillis()
                    if (remaining > 0) {
                        delay(remaining)
                        onAction(PlayerAction.UpdateSkipUndo(null, 0L, 0L))
                    }
                }
            }
            PlayerGestureIndicators(tapGestureState)
            DpadSeekIndicator(
                visible = keyInputState.isSeeking && keyInputState.seekOffsetMs != 0L,
                offsetMs = keyInputState.seekOffsetMs,
                positionMs = keyInputState.seekPositionMs,
            )

            var showCastSheet by remember { mutableStateOf(false) }
            val castActive = state.castState.isActive
            LaunchedEffect(castActive) {
                // Casting moves playback to the TV; keep the local session parked.
                if (castActive) player.pause()
            }
            if (showCastSheet && viewModel != null) {
                CastDeviceSheet(
                    viewModel = viewModel,
                    castUri = player.currentMediaItem?.mediaId.orEmpty(),
                    castTitle = metadataState.title.orEmpty().ifEmpty {
                        player.currentMediaItem?.mediaId?.substringAfterLast('/') ?: ""
                    },
                    castDurationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                    onDismiss = { showCastSheet = false },
                )
            }

            if (controlsVisibilityState.controlsVisible && controlsVisibilityState.controlsLocked) {
                PlayerLockedControls(
                    onUnlockControls = controlsVisibilityState::unlockControls,
                    unlockButtonModifier = Modifier.thenIf(isTv) {
                        focusRequester(unlockFocusRequester)
                            .onFocusChanged { isUnlockFocused = it.hasFocus }
                    },
                )
            } else {
                PlayerControls(
                    onBottomControlsHeightChanged = onBottomControlsHeightChanged,
                    player = player,
                    title = metadataState.title.orEmpty(),
                    playerPreferences = playerPreferences,
                    videoDecoderMode = state.decoderServiceState.videoMode,
                    controlsVisibilityState = controlsVisibilityState,
                    seekGestureState = seekGestureState,
                    videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                    progressState = progressState,
                    chaptersState = chaptersState,
                    isPipSupported = isPipSupported,
                    onShowOverlay = ::showOverlay,
                    onBackClick = { onAction(PlayerAction.NavigateUp) },
                    onPlayInBackgroundClick = { onAction(PlayerAction.PlayInBackground) },
                    onToggleTimeDisplay = { onAction(PlayerAction.ToggleTimeDisplay) },
                    onPictureInPictureClick = onPictureInPictureClick,
                    castState = state.castState,
                    onCastClick = {
                        controlsVisibilityState.showControls()
                        showCastSheet = true
                    },
                    onCastPlayPause = { onAction(PlayerAction.CastTogglePlayPause) },
                    onCastSeek = { onAction(PlayerAction.CastSeekTo(it)) },
                    onCastStop = { onAction(PlayerAction.CastStop) },
                    middleControlsModifier = Modifier.thenIf(isTv) {
                        focusRequester(middleControlsFocusRequester)
                            .onFocusChanged { isMiddleControlsFocused = it.hasFocus }
                    },
                )
            }

            OverlayShowView(
                player = player,
                overlayView = overlayView,
                chapters = chaptersState.chapters,
                currentChapterIndex = chaptersState.currentChapterIndex,
                onChapterSelected = { chapter ->
                    if (player.isCurrentMediaItemSeekable) {
                        player.seekTo(chapter.startTimeMs)
                        overlayView = null
                        controlsVisibilityState.showControls()
                    }
                },
                videoDecoderMode = state.decoderServiceState.videoMode,
                audioDecoderMode = state.decoderServiceState.audioMode,
                videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                onDismiss = { overlayView = null },
                onVideoDecoderModeSelected = { onAction(PlayerAction.SetVideoDecoderMode(it)) },
                onAudioDecoderModeSelected = { onAction(PlayerAction.SetAudioDecoderMode(it)) },
                onSelectSubtitleClick = { onAction(PlayerAction.SelectSubtitle) },
                onSelectAudioClick = { onAction(PlayerAction.SelectAudio) },
                onSubtitleOptionEvent = { onAction(PlayerAction.OnSubtitleOptionEvent(it)) },
                onVideoContentScaleChanged = videoZoomAndContentScaleState::onVideoContentScaleChanged,
            )
        }
    }

    BackHandler {
        when {
            overlayView != null -> overlayView = null
            isTv && controlsVisibilityState.controlsVisible -> controlsVisibilityState.hideControls()
            else -> onAction(PlayerAction.NavigateUp)
        }
    }
}
