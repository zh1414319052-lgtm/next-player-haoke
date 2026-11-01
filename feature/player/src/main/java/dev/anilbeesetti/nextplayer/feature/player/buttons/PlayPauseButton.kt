package dev.anilbeesetti.nextplayer.feature.player.buttons

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.LocalUseMaterialYouControls

@OptIn(UnstableApi::class)
@Composable
fun PlayPauseButton(
    modifier: Modifier = Modifier,
    player: Player?,
) {
    val state = rememberPlayPauseButtonState(player)
    val icon = when (state.showPlay) {
        true -> painterResource(coreUiR.drawable.ic_play)
        false -> painterResource(coreUiR.drawable.ic_pause)
    }
    val contentDescription = when (state.showPlay) {
        true -> stringResource(coreUiR.string.play_pause)
        false -> stringResource(coreUiR.string.play_pause)
    }
    val useMaterialYou = LocalUseMaterialYouControls.current

    PlayerButton(
        modifier = modifier.size(72.dp).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        enabled = state.isEnabled,
        containerColor = if (useMaterialYou) Color.Transparent else Color.Black.copy(alpha = 0.35f),
        onClick = state::onClick,
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = if (useMaterialYou) LocalContentColor.current else Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}
