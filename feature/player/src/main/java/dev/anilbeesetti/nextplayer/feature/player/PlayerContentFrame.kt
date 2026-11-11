package dev.anilbeesetti.nextplayer.feature.player

import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import dev.anilbeesetti.nextplayer.feature.player.extensions.toContentScale
import dev.anilbeesetti.nextplayer.feature.player.interpolation.InterpolationPlayerSurface
import dev.anilbeesetti.nextplayer.feature.player.state.PictureInPictureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVideoSurfaceSize
import dev.anilbeesetti.nextplayer.feature.player.state.VideoZoomAndContentScaleState
import dev.anilbeesetti.nextplayer.feature.player.ui.ShutterView
import dev.anilbeesetti.nextplayer.feature.player.ui.SubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.ui.SubtitleView
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun PlayerContentFrame(
    modifier: Modifier = Modifier,
    player: Player,
    pictureInPictureState: PictureInPictureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    subtitleConfiguration: SubtitleConfiguration,
    bottomControlsHeightPx: Int = 0,
) {
    val presentationState = rememberPresentationState(player)
    val videoSize = rememberVideoSurfaceSize(player)
    val density = LocalDensity.current
    val contentScale = videoZoomAndContentScaleState.videoContentScale.toContentScale()
    val videoSizePx = videoSize.takeIf { it.width > 0 && it.height > 0 }?.let {
        val ratio = it.pixelWidthHeightRatio
        val width = if (ratio < 1f) it.width * ratio else it.width.toFloat()
        val height = if (ratio > 1f) it.height / ratio else it.height.toFloat()
        Size(width, height)
    }
    val activity = LocalActivity.current as? PlayerActivity
    Box(modifier.fillMaxSize()) {
        InterpolationPlayerSurface(
            player = player,
            onSurfaceAvailable = { activity?.setInterpolationVideoSurface(it) },
            onSurfaceDestroyed = { activity?.clearInterpolationVideoSurface(it) },
            modifier = Modifier
                .resizeWithContentScale(
                    contentScale = contentScale,
                    sourceSizeDp = videoSizePx?.let {
                        with(density) { Size(it.width.toDp().value, it.height.toDp().value) }
                    },
                )
                .onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    val rect = Rect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt(),
                    )
                    pictureInPictureState.setVideoViewRect(rect)
                }
                .graphicsLayer {
                    scaleX = videoZoomAndContentScaleState.zoom
                    scaleY = videoZoomAndContentScaleState.zoom
                    translationX = videoZoomAndContentScaleState.offset.x
                    translationY = videoZoomAndContentScaleState.offset.y
                },
        )

        SubtitleView(
            player = player,
            isInPictureInPictureMode = pictureInPictureState.isInPictureInPictureMode,
            configuration = subtitleConfiguration,
            controlsHeightPx = bottomControlsHeightPx,
            videoBoundsModifier = videoSizePx?.let { Modifier.clipSubtitleBounds(it, contentScale) } ?: Modifier,
        )

        if (presentationState.coverSurface) {
            ShutterView()
        }
    }
}

/** Author-positioned captions use the visible picture; crop and 100% may overflow the surface. */
private fun Modifier.clipSubtitleBounds(videoSize: Size, contentScale: ContentScale): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || constraints.maxWidth == 0 || constraints.maxHeight == 0) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val viewport = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val scale = contentScale.computeScaleFactor(videoSize, viewport)
        val width = (videoSize.width * scale.scaleX).roundToInt().coerceIn(1, constraints.maxWidth)
        val height = (videoSize.height * scale.scaleY).roundToInt().coerceIn(1, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
        }
    }
}
