package dev.anilbeesetti.nextplayer.feature.player.interpolation

import android.os.Bundle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayerButton
import dev.anilbeesetti.nextplayer.feature.player.service.CustomCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

@Composable
fun InterpolationButton(enabled: Boolean = false, onClick: () -> Unit) {
    val title = stringResource(R.string.interpolation_title)
    PlayerButton(modifier = Modifier.semantics { contentDescription = title }, onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(NextIcons.Player, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White)
            Text(stringResource(R.string.interpolation_short), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun InterpolationDialog(player: Player?, onDismiss: () -> Unit) {
    val controller = player as? MediaController
    val view = LocalView.current
    var status by remember(controller) { mutableStateOf<Bundle?>(null) }
    var refreshRate by remember(view) { mutableStateOf(view.display?.refreshRate ?: 0f) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, busy, view) {
        while (controller != null && !busy) {
            refreshRate = view.display?.refreshRate ?: 0f
            try {
                val result = controller.sendCustomCommand(CustomCommands.GET_FRAME_INTERPOLATION.sessionCommand, Bundle.EMPTY).await()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) status = result.extras else failed = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                break
            }
            delay(1000)
        }
    }
    fun update(enabled: Boolean = status?.getBoolean("enabled") == true, target: Int = status?.getInt("target") ?: 60) {
        if (controller == null || status == null || busy) return
        busy = true
        scope.launch {
            try {
                val result = controller.sendCustomCommand(
                    CustomCommands.SET_FRAME_INTERPOLATION.sessionCommand,
                    Bundle().apply { putBoolean("enabled", enabled); putInt("target", target) },
                ).await()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    status = result.extras
                    failed = false
                } else failed = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally { busy = false }
        }
    }
    val statusText = when (status?.getString("status")) {
        "off" -> R.string.interpolation_off
        "active" -> R.string.interpolation_active
        "tunneling" -> R.string.interpolation_tunneling
        "decoder" -> R.string.interpolation_decoder
        "hdr" -> R.string.interpolation_hdr
        "speed" -> R.string.interpolation_speed
        "overload" -> R.string.interpolation_overload
        "resolution" -> R.string.interpolation_resolution
        "framerate" -> R.string.interpolation_framerate
        "failed" -> R.string.interpolation_failed
        else -> R.string.interpolation_waiting
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.interpolation_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.interpolation_enable), modifier = Modifier.weight(1f))
                    Switch(
                        checked = status?.getBoolean("enabled") == true,
                        enabled = controller != null && status != null && !busy,
                        onCheckedChange = { update(enabled = it) },
                    )
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((target, label) in listOf(60 to R.string.interpolation_fixed60, 0 to R.string.interpolation_double)) {
                        FilterChip(
                            selected = status?.getInt("target") == target,
                            enabled = controller != null && status != null && !busy,
                            onClick = { update(target = target) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                Text(stringResource(R.string.interpolation_explanation))
                Text(stringResource(if (failed) R.string.interpolation_command_failed else statusText))
                val fps = status?.getFloat("fps") ?: 0f
                if (fps > 0 && status?.getBoolean("enabled") == true) {
                    val target = status?.getInt("target") ?: 60
                    Text(stringResource(R.string.interpolation_metrics, fps, if (target == 0) fps * 2 else target.toFloat(), status?.getFloat("ms") ?: 0f))
                    Text(stringResource(R.string.interpolation_count, status?.getLong("emitted") ?: 0L, status?.getLong("presented") ?: 0L), style = MaterialTheme.typography.bodySmall)
                }
                if (refreshRate > 0f) {
                    Text(stringResource(R.string.interpolation_screen_refresh, refreshRate), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.interpolation_done)) } },
    )
}
