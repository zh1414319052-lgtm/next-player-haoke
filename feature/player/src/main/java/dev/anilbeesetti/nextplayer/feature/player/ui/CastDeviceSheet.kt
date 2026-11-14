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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.cast.model.CastState
import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import dev.anilbeesetti.nextplayer.core.cast.model.discoveryKey
import dev.anilbeesetti.nextplayer.core.cast.model.mergeDlnaDevices
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.player.PlayerAction
import dev.anilbeesetti.nextplayer.feature.player.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Device picker for casting: live SSDP discovery plus a manual IP probe for
 * networks that suppress multicast. Selecting a device starts a direct-play
 * session, with an explicit confirmation when the TV did not report support
 * for the media format.
 */
@Composable
fun CastDeviceSheet(
    viewModel: PlayerViewModel,
    castUri: String,
    castTitle: String,
    castDurationMs: Long,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deviceFlow = remember(viewModel) { viewModel.castDevices }
    CastDeviceSheet(
        deviceFlow = deviceFlow,
        castState = state.castState,
        probeDevice = viewModel::probeCastDevice,
        checkCompatibility = { viewModel.castCompatibility(it, castUri, castTitle, castDurationMs) },
        startCast = { viewModel.onAction(PlayerAction.CastStart(it, castUri, castTitle, castDurationMs)) },
        stopCast = { viewModel.onAction(PlayerAction.CastStop) },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSheet(
    deviceFlow: Flow<List<DlnaDevice>>,
    castState: CastState,
    probeDevice: suspend (String) -> DlnaDevice?,
    checkCompatibility: suspend (DlnaDevice) -> Pair<Boolean?, String>,
    startCast: (DlnaDevice) -> Unit,
    stopCast: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(emptyList<DlnaDevice>()) }
    var scanning by remember { mutableStateOf(true) }
    var manualIp by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var pendingDevice by remember { mutableStateOf<DlnaDevice?>(null) }
    var pendingMime by remember { mutableStateOf("") }

    LaunchedEffect(deviceFlow) {
        try {
            deviceFlow.collect { list ->
                devices = mergeDlnaDevices(devices, list)
                scanning = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep manual results and allow a new scan when the sheet is reopened.
            scanning = false
        }
    }
    // Discovery only emits when a renderer resolves; stop the spinner eventually.
    LaunchedEffect(Unit) {
        delay(DISCOVERY_SETTLE_MS)
        scanning = false
    }
    LaunchedEffect(castState.isActive) {
        if (castState.isActive) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.cast_to_device),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            castState.error?.let { error ->
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
                    items(devices, key = { it.discoveryKey }) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val (incompatible, mime) = checkCompatibility(device)
                                        if (incompatible == true) {
                                            pendingMime = mime
                                            pendingDevice = device
                                        } else {
                                            startCast(device)
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
                            try {
                                probeDevice(host)?.let { found ->
                                    devices = mergeDlnaDevices(devices, listOf(found))
                                    scanning = false
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // A failed address must not crash the picker or disable retries.
                            } finally {
                                probing = false
                            }
                        }
                    },
                    enabled = manualIp.isNotBlank() && !probing,
                ) {
                    Text(text = stringResource(R.string.cast_add))
                }
            }

            TextButton(
                onClick = {
                    stopCast()
                    onDismiss()
                },
                enabled = castState.isActive,
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
                    startCast(device)
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
