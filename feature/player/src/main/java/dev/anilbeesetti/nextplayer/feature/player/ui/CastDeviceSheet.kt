package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.player.PlayerAction
import dev.anilbeesetti.nextplayer.feature.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Device picker for casting: live SSDP discovery plus a manual IP probe for
 * networks that suppress multicast. Selecting a device starts a direct-play
 * session, with an explicit confirmation when the TV did not report support
 * for the media format.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSheet(
    viewModel: PlayerViewModel,
    castUri: String,
    castTitle: String,
    castDurationMs: Long,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<DlnaDevice>() }
    var scanning by remember { mutableStateOf(true) }
    var manualIp by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var pendingDevice by remember { mutableStateOf<DlnaDevice?>(null) }
    var pendingMime by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching {
            viewModel.castDevices.collect { list ->
                devices.clear()
                devices.addAll(list)
                scanning = false
            }
        }
    }
    // Discovery only emits when a renderer resolves; stop the spinner eventually.
    LaunchedEffect(Unit) {
        delay(DISCOVERY_SETTLE_MS)
        scanning = false
    }
    LaunchedEffect(Unit) {
        viewModel.state.collect {
            if (it.castState.isActive) onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.cast_to_device),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            state.castState.error?.let { error ->
                Text(
                    text = stringResource(R.string.cast_failed, error.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            when {
                scanning -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(text = stringResource(R.string.cast_scan_devices))
                }
                devices.isEmpty() -> Text(
                    text = stringResource(R.string.cast_no_devices),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(devices, key = { it.udn }) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val (incompatible, mime) = viewModel.castCompatibility(
                                            device, castUri, castTitle, castDurationMs,
                                        )
                                        if (incompatible == true) {
                                            pendingMime = mime
                                            pendingDevice = device
                                        } else {
                                            viewModel.onAction(
                                                PlayerAction.CastStart(device, castUri, castTitle, castDurationMs),
                                            )
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(imageVector = NextIcons.Cast, contentDescription = null)
                            Column {
                                Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = device.udn.substringAfter("uuid:").take(28),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    label = { Text(stringResource(R.string.cast_ip_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val host = manualIp.trim()
                        if (host.isEmpty() || probing) return@TextButton
                        probing = true
                        scope.launch {
                            viewModel.probeCastDevice(host)?.let { found ->
                                if (devices.none { it.udn == found.udn }) devices.add(found)
                                scanning = false
                            }
                            probing = false
                        }
                    },
                    enabled = manualIp.isNotBlank() && !probing,
                ) {
                    Text(text = stringResource(R.string.cast_add))
                }
            }

            TextButton(
                onClick = {
                    viewModel.onAction(PlayerAction.CastStop)
                    onDismiss()
                },
                enabled = state.castState.isActive,
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                Text(text = stringResource(R.string.cast_stop))
            }
        }
    }

    pendingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingDevice = null },
            title = { Text(stringResource(R.string.cast_likely_incompatible_title)) },
            text = { Text(stringResource(R.string.cast_likely_incompatible_message, pendingMime)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onAction(PlayerAction.CastStart(device, castUri, castTitle, castDurationMs))
                    pendingDevice = null
                }) { Text(stringResource(R.string.cast_try_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDevice = null }) { Text(stringResource(R.string.cast_dismiss)) }
            },
        )
    }
}

private const val DISCOVERY_SETTLE_MS = 8_000L
