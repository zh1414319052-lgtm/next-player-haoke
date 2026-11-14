package dev.anilbeesetti.nextplayer.core.cast.discovery

import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import dev.anilbeesetti.nextplayer.core.cast.model.mergeDlnaDevices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.runningFold

/** Deduplicate requests per scan and serialize completed descriptions into unique snapshots. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun resolveDlnaDevices(
    locations: Flow<String>,
    loadDevice: suspend (String) -> DlnaDevice?,
): Flow<List<DlnaDevice>> = flow {
    val requested = mutableSetOf<String>()
    emitAll(
        locations
            .filter { requested.add(it) }
            .flatMapMerge(concurrency = 4) { location ->
                flow { loadDevice(location)?.let { emit(it) } }
            }
            // One collector owns the list, so slow requests cannot publish an older snapshot.
            .runningFold(emptyList<DlnaDevice>()) { devices, device ->
                mergeDlnaDevices(devices, listOf(device))
            }
            .drop(1)
            .distinctUntilChanged(),
    )
}
