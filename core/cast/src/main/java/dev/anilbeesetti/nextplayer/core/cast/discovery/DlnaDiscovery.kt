package dev.anilbeesetti.nextplayer.core.cast.discovery

import android.content.Context
import android.net.wifi.WifiManager
import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Discovers DLNA MediaRenderers with SSDP M-SEARCH and can probe one address
 * directly (unicast M-SEARCH), which rescues networks where the router
 * suppresses multicast between Wi-Fi and Ethernet clients.
 *
 * Design follows the open-source UPnPCast control point (MIT,
 * github.com/yinnho/UPnPCast), re-implemented with ephemeral ports, manual
 * probing and strict response parsing.
 */
class DlnaDiscovery(private val context: Context) {

    /** Emits the current renderer list until the collecting scope is cancelled. */
    fun discover(): Flow<List<DlnaDevice>> = callbackFlow {
        val lock = acquireMulticastLock()
        val socket = DatagramSocket().apply {
            soTimeout = RESPONSE_POLL_MS
            broadcast = true
        }
        val found = LinkedHashMap<String, DlnaDevice?>()
        val fetchJobs = mutableListOf<kotlinx.coroutines.Job>()
        val searchJob = launch(Dispatchers.IO) {
            runCatching { sendSearch(socket, MEDIA_RENDERER_ST) }
            runCatching { sendSearch(socket, SSDP_ALL) }
        }
        val receiveJob = launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + DISCOVERY_WINDOW_MS
            val buffer = ByteArray(2048)
            try {
                while (isActive && System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
                    val location = parseSsdpResponseLocation(text) ?: continue
                    val key = parseSsdpResponseUsn(text) ?: location
                    synchronized(found) {
                        if (found.containsKey(key)) return@synchronized
                        found[key] = null
                    }
                    fetchJobs += launch(Dispatchers.IO) {
                        val device = loadDevice(location)
                        synchronized(found) { found[key] = device }
                        val resolved = synchronized(found) { found.values.filterNotNull() }
                        if (resolved.isNotEmpty()) send(resolved.sortedBy { it.name })
                    }
                }
            } finally {
                socket.close()
            }
        }
        awaitClose {
            searchJob.cancel()
            receiveJob.cancel()
            fetchJobs.forEach { it.cancel() }
            runCatching { socket.close() }
            lock.release()
        }
    }

    /** Unicast M-SEARCH probe of one host, for manual entry or multicast-free networks. */
    suspend fun probe(host: String): DlnaDevice? = withContext(Dispatchers.IO) {
        val lock = acquireMulticastLock()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = RESPONSE_POLL_MS
                sendSearch(socket, MEDIA_RENDERER_ST, targetAddress = InetAddress.getByName(host))
                val deadline = System.currentTimeMillis() + PROBE_WINDOW_MS
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
                    val location = parseSsdpResponseLocation(text) ?: continue
                    loadDevice(location)?.let { return@withContext it }
                }
            }
        } finally {
            lock.release()
        }
        null
    }

    /** Fetches and parses a device description document. */
    suspend fun loadDevice(locationUrl: String): DlnaDevice? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet(locationUrl) ?: return@withContext null
            val parsed = DeviceDescriptionParser.parse(locationUrl, body) ?: return@withContext null
            val protocolInfo = parsed.avTransportScpdUrl?.let { scpd ->
                runCatching { DeviceDescriptionParser.parseProtocolInfo(httpGet(scpd).orEmpty()) }
                    .getOrDefault(emptySet())
            }.orEmpty()
            parsed.copy(sinkProtocolInfo = protocolInfo)
        }.getOrNull()
    }

    internal fun httpGet(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = CONNECT_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return null
        }
        connection.inputStream.use { stream ->
            val bytes = stream.readBytes()
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun acquireMulticastLock(): WifiManager.MulticastLock {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("nextplayer-dlna").apply { setReferenceCounted(false) }
        // The lock only improves advertisement reception; some environments gate it.
        runCatching { lock.acquire() }
        return lock
    }

    private fun sendSearch(socket: DatagramSocket, st: String, targetAddress: InetAddress? = null) {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$MULTICAST_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: $MX_SECONDS\r\n")
            append("ST: $st\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val address = targetAddress ?: InetAddress.getByName(SSDP_ADDRESS)
        val packet = DatagramPacket(request, request.size, address, MULTICAST_PORT)
        repeat(2) {
            socket.send(packet)
            Thread.sleep(150)
        }
    }

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val MULTICAST_PORT = 1900
        const val SSDP_ALL = "ssdp:all"
        const val MEDIA_RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val MX_SECONDS = 3
        private const val RESPONSE_POLL_MS = 300
        private const val DISCOVERY_WINDOW_MS = 7_000L
        private const val PROBE_WINDOW_MS = 3_500L
        private const val CONNECT_TIMEOUT_MS = 3_000

        fun parseSsdpResponseLocation(response: String): String? =
            response.lineSequence()
                .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

        fun parseSsdpResponseUsn(response: String): String? =
            response.lineSequence()
                .firstOrNull { it.startsWith("USN:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
    }
}
