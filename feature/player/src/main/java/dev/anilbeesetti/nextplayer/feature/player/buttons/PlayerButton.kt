package dev.anilbeesetti.nextplayer.feature.player.buttons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.feature.player.LocalUseMaterialYouControls

val PlayerButtonBlackAlpha = Color.Black.copy(alpha = 0.3f)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val isTv = remember { context.isTelevision }

    val colors = if (LocalUseMaterialYouControls.current) {
        IconButtonDefaults.filledTonalIconButtonColors()
    } else {
        IconButtonDefaults.iconButtonColors(
            contentColor = Color.White,
            containerColor = containerColor,
        )
    }

    val rippleConfiguration = if (LocalUseMaterialYouControls.current) {
        LocalRippleConfiguration.current
    } else {
        RippleConfiguration(
            color = Color.White,
            rippleAlpha = RippleAlpha(
                pressedAlpha = 0.5f,
                focusedAlpha = 0.5f,
                draggedAlpha = 0.5f,
                hoveredAlpha = 0.5f
            )
        )
    }

    CompositionLocalProvider(
        LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor,
        LocalRippleConfiguration provides rippleConfiguration
    ) {
        Box(
            modifier = modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(CircleShape)
                .background(if (enabled) colors.containerColor else colors.disabledContainerColor)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(contentPadding)
                .tvFocusRing(isTv),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}
