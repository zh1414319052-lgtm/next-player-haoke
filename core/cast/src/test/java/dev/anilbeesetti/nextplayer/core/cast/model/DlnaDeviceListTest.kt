package dev.anilbeesetti.nextplayer.core.cast.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaDeviceListTest {
    @Test
    fun repeatedUdnAndUuidCaseVariantsUpdateOneDeviceWhileSameNamesStaySeparate() {
        val first = device(" UUID:23938114-6002-7277-3000-683943DC7E0A ", "old")
        val updated = first.copy(udn = first.udn.trim().lowercase(), locationUrl = "new", avTransportUrl = "new/control")
        val other = device("uuid:other", "other")
        val devices = mergeDlnaDevices(listOf(first, first, other), listOf(updated, updated))

        assertEquals(2, devices.size)
        assertEquals(updated, devices.single { it.discoveryKey == first.discoveryKey })
    }

    @Test
    fun automaticRefreshKeepsManualDeviceAndEmptyUpdatesKeepExistingRows() {
        val manual = device("uuid:manual", "manual")
        val automatic = device("uuid:auto", "auto")
        val refreshed = mergeDlnaDevices(listOf(manual), listOf(automatic, automatic))
        assertEquals(setOf(manual, automatic), refreshed.toSet())
        assertEquals(refreshed, mergeDlnaDevices(refreshed, emptyList()))
        assertEquals(2, mergeDlnaDevices(refreshed, listOf(manual)).size)
    }

    @Test
    fun missingUdnFallsBackToCaseSensitiveDescriptionLocation() {
        val devices = mergeDlnaDevices(emptyList(), listOf(device("", "http://tv/A"), device(" ", "http://tv/a")))
        assertEquals(2, devices.size)
    }

    private fun device(udn: String, location: String) = DlnaDevice(
        name = "TV",
        udn = udn,
        locationUrl = location,
        avTransportUrl = "$location/control",
        renderingControlUrl = null,
    )
}
