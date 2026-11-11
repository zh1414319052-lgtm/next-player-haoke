package dev.anilbeesetti.nextplayer.feature.player.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.anilbeesetti.nextplayer.core.model.Font
import dev.anilbeesetti.nextplayer.feature.player.extensions.toTypeface
import dev.anilbeesetti.nextplayer.feature.player.state.rememberCuesState

@OptIn(UnstableApi::class)
@Composable
fun SubtitleView(
    modifier: Modifier = Modifier,
    player: Player,
    isInPictureInPictureMode: Boolean,
    configuration: SubtitleConfiguration,
    controlsHeightPx: Int = 0,
    videoBoundsModifier: Modifier = Modifier,
) {
    val cuesState = rememberCuesState(player)
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val defaultTextSizePx = if (isInPictureInPictureMode) {
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * viewportHeightPx
    } else {
        with(LocalDensity.current) { configuration.textSize.sp.toPx() }
    }
    val cueGroup = cuesState.currentCues
    val usesVideoBounds = cueGroup?.cues?.any {
        it.line != Cue.DIMEN_UNSET || it.position != Cue.DIMEN_UNSET ||
            it.textSizeType == Cue.TEXT_SIZE_TYPE_FRACTIONAL || it.textSizeType == Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING
    } == true
    val displayCues = remember(cueGroup, viewportHeightPx, defaultTextSizePx, configuration.applyEmbeddedStyles) {
        stackSubtitleCues(cueGroup?.cues.orEmpty(), viewportHeightPx, defaultTextSizePx, configuration.applyEmbeddedStyles)
    }

    AndroidView(
        modifier = modifier
            .then(if (usesVideoBounds) videoBoundsModifier else Modifier)
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height },
        factory = { context ->
            SubtitleView(context).apply {
                val captioningManager = getSystemService(context, CaptioningManager::class.java) ?: return@apply
                if (configuration.useSystemCaptionStyle) {
                    val systemCaptionStyle = CaptionStyleCompat.createFromCaptionStyle(captioningManager.userStyle)
                    setStyle(systemCaptionStyle)
                } else {
                    val userStyle = CaptionStyleCompat(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.BLACK.takeIf { configuration.showBackground } ?: android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                        android.graphics.Color.BLACK,
                        Typeface.create(
                            if (configuration.font == Font.DEFAULT) {
                                androidx.core.content.res.ResourcesCompat.getFont(context, dev.anilbeesetti.nextplayer.core.ui.R.font.noto_sans_sc)
                            } else {
                                configuration.font.toTypeface()
                            },
                            Typeface.BOLD.takeIf { configuration.textBold } ?: Typeface.NORMAL,
                        ),
                    )
                    setStyle(userStyle)
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, configuration.textSize.toFloat())
                }
            }
        },
        update = { subtitleView ->
            subtitleView.setBottomPaddingFraction(
                subtitleBottomPaddingFraction(viewportHeightPx, if (usesVideoBounds) 0 else controlsHeightPx, isInPictureInPictureMode),
            )
            subtitleView.setApplyEmbeddedStyles(configuration.applyEmbeddedStyles)
            subtitleView.setCues(displayCues)
            if (isInPictureInPictureMode) {
                subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            } else {
                subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, configuration.textSize.toFloat())
            }
        },
    )
}

@Stable
data class SubtitleConfiguration(
    val useSystemCaptionStyle: Boolean,
    val showBackground: Boolean,
    val font: Font,
    val textSize: Int,
    val textBold: Boolean,
    val applyEmbeddedStyles: Boolean,
)
