package dev.anilbeesetti.nextplayer.core.cast.model

import java.util.Locale

/** SSDP service USNs and description URLs are not renderer identities. */
val DlnaDevice.discoveryKey: String
    get() = udn.trim().ifEmpty { locationUrl.trim() }.let { identity ->
        if (identity.startsWith("uuid:", ignoreCase = true)) identity.lowercase(Locale.ROOT) else identity
    }

/** Shared by automatic discovery and manual probing; the newest description wins. */
fun mergeDlnaDevices(existing: List<DlnaDevice>, incoming: List<DlnaDevice>): List<DlnaDevice> {
    val devices = LinkedHashMap<String, DlnaDevice>()
    existing.forEach { devices[it.discoveryKey] = it }
    incoming.forEach { devices[it.discoveryKey] = it }
    return devices.values.sortedWith(compareBy<DlnaDevice> { it.name }.thenBy { it.discoveryKey })
}
