package dev.anilbeesetti.nextplayer.feature.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationButton
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayerButton
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import dev.anilbeesetti.nextplayer.core.model.ControlButtonsPosition
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.feature.player.extensions.nameRes
import dev.anilbeesetti.nextplayer.feature.player.state.ChaptersState
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.SeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.VideoZoomAndContentScaleState
import dev.anilbeesetti.nextplayer.feature.player.state.seekAmountFormatted
import dev.anilbeesetti.nextplayer.feature.player.state.seekToPositionFormated
import dev.anilbeesetti.nextplayer.feature.player.ui.InfoView
import dev.anilbeesetti.nextplayer.feature.player.ui.OverlayView
import dev.anilbeesetti.nextplayer.core.cast.model.CastState
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.CastMiddleView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsBottomView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsMiddleView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsTopView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode

@OptIn(UnstableApi::class)
@Composable
fun PlayerControls(
    player: Player?,
    title: String,
    playerPreferences: PlayerPreferences,
    videoDecoderMode: DecoderMode?,
    controlsVisibilityState: ControlsVisibilityState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    progressState: ProgressStateWithTickInterval,
    chaptersState: ChaptersState,
    isPipSupported: Boolean,
    onShowOverlay: (OverlayView) -> Unit,
    onBackClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    onToggleTimeDisplay: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    castState: CastState?,
    onCastClick: () -> Unit,
    onCastPlayPause: () -> Unit,
    onCastSeek: (Long) -> Unit,
    onCastStop: () -> Unit,
    modifier: Modifier = Modifier,
    middleControlsModifier: Modifier = Modifier,
    onBottomControlsHeightChanged: (Int) -> Unit = {},
    onCaptureFrameClick: () -> Unit = {},
    canCaptureFrame: Boolean = false,
    isCapturingFrame: Boolean = false,
) {
    var topHeightPx by remember { mutableIntStateOf(0) }
    var bottomHeightPx by remember { mutableIntStateOf(0) }
    var subtitleRailHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var showInterpolation by rememberSaveable { mutableStateOf(false) }
    if (showInterpolation) {
        InterpolationDialog(player = player, onDismiss = { showInterpolation = false })
    }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // In a short landscape window, center transport controls in the remaining
        // content area and leave room for two default subtitle lines above the rail.
        val middleOffset = if (maxWidth > maxHeight && topHeightPx > 0 && bottomHeightPx > 0) {
            val top = with(density) { topHeightPx.toDp() }
            val bottom = with(density) { bottomHeightPx.toDp() }
            val captions = (playerPreferences.subtitleTextSize * density.fontScale * 2).dp
            val center = ((top + maxHeight - bottom - captions) / 2)
                .coerceAtLeast(top + 44.dp)
                .coerceAtMost(maxHeight / 2)
            center - maxHeight / 2
        } else {
            0.dp
        }
        val transportModifier = middleControlsModifier.offset(y = middleOffset)
        LaunchedEffect(maxWidth, maxHeight, bottomHeightPx, subtitleRailHeightPx) {
            // Portrait captions also clear the full-width time row; in landscape
            // they use the open center of that row above the seekbar/action rail.
            onBottomControlsHeightChanged(if (maxWidth > maxHeight) subtitleRailHeightPx else bottomHeightPx)
        }
        Column {
            AnimatedVisibility(
                visible = controlsVisibilityState.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ControlsTopView(
                    modifier = Modifier.onSizeChanged { topHeightPx = it.height },
                    title = title,
                    videoDecoderMode = videoDecoderMode,
                    onDecoderClick = { onShowOverlay(OverlayView.DECODER_SELECTOR) },
                    onPlaylistClick = { onShowOverlay(OverlayView.PLAYLIST) },
                    onBackClick = onBackClick,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(
                visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ControlsBottomView(
                    modifier = Modifier.onSizeChanged {
                        bottomHeightPx = it.height
                    },
                    onSubtitleInsetChanged = { subtitleRailHeightPx = it },
                    player = player,
                    progressState = progressState,
                    chaptersState = chaptersState,
                    onChaptersClick = { onShowOverlay(OverlayView.CHAPTERS) },
                    controlsAlignment = when (playerPreferences.controlButtonsPosition) {
                        ControlButtonsPosition.LEFT -> Alignment.Start
                        ControlButtonsPosition.RIGHT -> Alignment.End
                    },
                    videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                    isPipSupported = isPipSupported,
                    showRemainingTime = playerPreferences.showRemainingTime,
                    onToggleTimeDisplay = onToggleTimeDisplay,
                    onSeek = seekGestureState::onSeek,
                    onSeekEnd = seekGestureState::onSeekEnd,
                    onPlaybackSpeedClick = { onShowOverlay(OverlayView.PLAYBACK_SPEED) },
                    onPlayInBackgroundClick = onPlayInBackgroundClick,
                    onLockControlsClick = {
                        controlsVisibilityState.showControls()
                        controlsVisibilityState.lockControls()
                    },
                    onVideoContentScaleClick = {
                        controlsVisibilityState.showControls()
                        videoZoomAndContentScaleState.switchToNextVideoContentScale()
                    },
                    onVideoContentScaleLongClick = { onShowOverlay(OverlayView.VIDEO_CONTENT_SCALE) },
                    onPictureInPictureClick = onPictureInPictureClick,
                    castState = castState,
                    onCastClick = onCastClick,
                    onCastPlayPause = onCastPlayPause,
                    onCastSeek = onCastSeek,
                    quickActions = {
                        CinemaQuickAction(coreUiR.drawable.ic_audio_track, stringResource(coreUiR.string.cinema_audio)) {
                            onShowOverlay(OverlayView.AUDIO_SELECTOR)
                        }
                        CinemaQuickAction(coreUiR.drawable.ic_subtitle_track, stringResource(coreUiR.string.cinema_subtitles)) {
                            onShowOverlay(OverlayView.SUBTITLE_SELECTOR)
                        }
                        if (castState?.isActive != true) {
                            CinemaQuickAction(
                                coreUiR.drawable.ic_frame_capture,
                                stringResource(if (isCapturingFrame) coreUiR.string.frame_capture_working else coreUiR.string.frame_capture),
                                enabled = canCaptureFrame && !isCapturingFrame,
                                onClick = onCaptureFrameClick,
                            )
                        }
                        InterpolationButton(
                            enabled = playerPreferences.frameInterpolationEnabled,
                            onClick = { showInterpolation = true },
                        )
                    },
                )
            }
        }
        when {
            seekGestureState.seekAmount != null -> InfoView(info = "${seekGestureState.seekAmountFormatted}\n[${seekGestureState.seekToPositionFormated}]")
            videoZoomAndContentScaleState.isZooming -> InfoView(info = "${(videoZoomAndContentScaleState.zoom * 100).toInt()}%")
            videoZoomAndContentScaleState.showContentScaleIndicator -> InfoView(info = stringResource(videoZoomAndContentScaleState.videoContentScale.nameRes()))
            controlsVisibilityState.controlsVisible -> if (castState?.isActive == true) {
                CastMiddleView(
                    isPlaying = castState.isPlaying,
                    onPlayPause = onCastPlayPause,
                    onStop = onCastStop,
                    modifier = transportModifier,
                )
            } else {
                ControlsMiddleView(
                    player = player,
                    modifier = transportModifier,
                )
            }
        }
    }
}

@Composable
private fun CinemaQuickAction(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    PlayerButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
