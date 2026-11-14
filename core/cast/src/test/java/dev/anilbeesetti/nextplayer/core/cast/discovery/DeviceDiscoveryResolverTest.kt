package dev.anilbeesetti.nextplayer.core.cast.discovery

import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import dev.anilbeesetti.nextplayer.core.cast.model.discoveryKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDiscoveryResolverTest {
    @Test
    fun serviceAdvertisementsAndRepeatedPacketsLoadOneDescriptionOnly() = runTest {
        val location = "http://192.168.1.20/dd.xml"
        val responses = listOf("uuid:tv", "uuid:tv::upnp:rootdevice", "uuid:tv::urn:schemas-upnp-org:service:AVTransport:1")
            .flatMap { usn ->
                List(10) { "HTTP/1.1 200 OK\r\nLOCATION: $location\r\nUSN: $usn\r\n\r\n" }
            }
        val locations = responses.map { DlnaDiscovery.parseSsdpResponseLocation(it)!! }
        var requests = 0
        val snapshots = resolveDlnaDevices(locations.asFlow()) {
            requests++
            delay(100)
            device("uuid:tv", it)
        }.toList()

        assertEquals(1, requests)
        assertEquals(1, snapshots.single().size)
    }

    @Test
    fun concurrentDescriptionsForSameRendererNeverPublishDuplicateKeysOrLoseOtherDevices() = runTest {
        val locations = listOf("slow", "other", "fast")
        val snapshots = resolveDlnaDevices(locations.asFlow()) { location ->
            delay(if (location == "slow") 300 else 50)
            device(if (location == "other") "uuid:other" else "uuid:23938114-6002-7277-3000-683943DC7E0A", location)
        }.toList()

        snapshots.forEach { assertEquals(it.size, it.map(DlnaDevice::discoveryKey).distinct().size) }
        assertEquals(2, snapshots.last().size)
        assertEquals("slow", snapshots.last().single { it.udn != "uuid:other" }.locationUrl)
        assertTrue(snapshots.zipWithNext().all { (before, after) -> after.size >= before.size })
    }

    @Test
    fun missingRendererDoesNotRemoveResolvedDevicesAndReopeningRetriesDiscovery() = runTest {
        var attempts = 0
        val discovery = resolveDlnaDevices(flowOf("missing", "tv", "tv")) {
            attempts++
            if (it == "missing") null else device("uuid:tv", it)
        }
        assertEquals(1, discovery.toList().last().size)
        assertEquals(1, discovery.toList().last().size)
        assertEquals(4, attempts)
    }

    @Test
    fun closingDiscoveryCancelsPendingLoadsAndLimitsConcurrentRequests() = runTest {
        var running = 0
        var cancelled = 0
        val job = launch {
            resolveDlnaDevices((1..100).map(Int::toString).asFlow()) {
                running++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }.collect()
        }
        runCurrent()
        assertEquals(4, running)
        job.cancelAndJoin()
        assertEquals(running, cancelled)
    }

    private fun device(udn: String, location: String) = DlnaDevice(
        name = "TV",
        udn = udn,
        locationUrl = location,
        avTransportUrl = "$location/control",
        renderingControlUrl = null,
    )
}
