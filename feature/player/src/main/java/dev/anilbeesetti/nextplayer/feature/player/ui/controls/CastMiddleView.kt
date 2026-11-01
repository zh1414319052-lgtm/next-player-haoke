package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.feature.player.LocalUseMaterialYouControls
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayerButton
import dev.anilbeesetti.nextplayer.feature.player.ui.preview.rememberPreviewPlayer
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow

/** Central transport while casting: play/pause and stop; seek lives in the bottom bar. */
@Composable
fun CastMiddleView(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPauseFocusRequester = remember { FocusRequester() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { onEnter = { playPauseFocusRequester.requestFocus() } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(40.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerButton(
        onClick = onStop,
        containerColor = if (LocalUseMaterialYouControls.current) Color.Transparent else Color.Black.copy(alpha = 0.3f),
    ) {
            Icon(imageVector = Icons.Rounded.Stop, contentDescription = stringResource(R.string.cast_stop))
        }
        PlayerButton(
            onClick = onPlayPause,
            modifier = Modifier.focusRequester(playPauseFocusRequester),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
            )
        }
    }
}

@Preview
@Composable
private fun CastMiddleViewPreview() {
    NextPlayerTheme(darkTheme = true) {
        Surface {
            CastMiddleView(isPlaying = true, onPlayPause = {}, onStop = {})
        }
    }
}
