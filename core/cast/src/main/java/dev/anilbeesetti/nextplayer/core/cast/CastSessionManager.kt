package dev.anilbeesetti.nextplayer.core.cast

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.anilbeesetti.nextplayer.core.cast.control.AvTransportController
import dev.anilbeesetti.nextplayer.core.cast.control.RenderingControlController
import dev.anilbeesetti.nextplayer.core.cast.control.UpnpControlException
import dev.anilbeesetti.nextplayer.core.cast.discovery.DlnaDiscovery
import dev.anilbeesetti.nextplayer.core.cast.model.CastError
import dev.anilbeesetti.nextplayer.core.cast.model.CastMedia
import dev.anilbeesetti.nextplayer.core.cast.model.CastState
import dev.anilbeesetti.nextplayer.core.cast.model.CastTransportState
import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import dev.anilbeesetti.nextplayer.core.cast.server.MediaHttpServer
import dev.anilbeesetti.nextplayer.core.cast.util.UpnpTime
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import java.io.File
import java.net.NetworkInterface
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * Owns the active DLNA cast session: exposes discovered renderers, starts
 * direct-play sessions backed by [MediaHttpServer], mirrors remote state to
 * [state] and classifies failures so the UI can tell connection problems from
 * likely format incompatibility.
 */
@Single
class CastSessionManager(
    private val context: Context,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val scope: CoroutineScope,
) {
    private val discovery = DlnaDiscovery(context)
    private val avController = AvTransportController()
    private val renderingController = RenderingControlController()
    private val server = MediaHttpServer()

    private val stateFlow = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = stateFlow

    private var pollJob: Job? = null
    private var volume: Int? = null

    /**
     * Test hook: when set, cast URLs advertise this host instead of the
     * auto-detected local address (used behind emulator NAT with adb forward).
     */
    @Volatile
    var advertisedHostOverride: String? = null

    /** Live SSDP discovery of renderers on the current network. */
    fun discoverDevices(): Flow<List<DlnaDevice>> = discovery.discover()

    /** Unicast probe used by the manual-address entry in the device picker. */
    suspend fun probeDevice(host: String): DlnaDevice? = discovery.probe(host)

    /** Parses a renderer from its description URL; used when SSDP is filtered. */
    suspend fun describeDevice(locationUrl: String): DlnaDevice? = discovery.loadDevice(locationUrl)

    /**
     * Best-effort format compatibility hint from the renderer's sink protocol
     * info: null when the device did not declare capabilities, true when the
     * media MIME type is missing from a non-empty declaration.
     */
    fun isLikelyIncompatible(device: DlnaDevice, media: CastMedia): Boolean? {
        val sinks = device.sinkProtocolInfo
        if (sinks.isEmpty()) return null
        val supported = sinks.any { entry ->
            val fields = entry.split(":")
            fields.size >= 3 && (
                fields[2].trim().equals(media.mime, ignoreCase = true) ||
                    fields[2].trim() == "*" ||
                    fields[1].trim() == "*" && fields[2].trim() == "*"
                )
        }
        return !supported
    }

    /**
     * Starts a direct-play cast session. Returns the resulting state; on
     * failure [CastState.error] explains what to show the user.
     */
    suspend fun startCast(device: DlnaDevice, media: CastMedia): CastState {
        stopCast(silent = true)
        val serverSession = MediaHttpServer.Session(
            token = UUID.randomUUID().toString().replace("-", ""),
            mime = media.mime,
            lengthBytes = media.sizeBytes ?: 0L,
            openDescriptor = { openMediaDescriptor(media.uri) },
        )
        server.session = serverSession
        val port = try {
            server.start()
        } catch (error: Exception) {
            server.session = null
            return updateError(CastError(possiblyIncompatible = false, message = "无法在本机建立媒体服务：${error.message}"))
        }
        val address = localAddress()
        if (address == null) {
            server.stop()
            server.session = null
            return updateError(CastError(possiblyIncompatible = false, message = "未找到可用的局域网地址，请确认设备已连接同一网络"))
        }
        val castUrl = "http://$address:$port/media/${serverSession.token}"
        Logger.logInfo(TAG, "Casting ${media.title} to ${device.name} via $castUrl")

        try {
            avController.setAvTransportUri(device, castUrl, buildDidl(media, castUrl))
            avController.play(device)
        } catch (error: Exception) {
            server.stop()
            server.session = null
            val incompatible = error is UpnpControlException && INCOMPATIBLE_FAULT_CODES.contains(error.errorCode)
            return updateError(
                CastError(
                    possiblyIncompatible = incompatible,
                    message = "连接 ${device.name} 失败：${error.message}",
                ),
            )
        }

        val volume = renderingController.getVolume(device)
        stateFlow.value = CastState(
            device = device,
            media = media,
            transportState = CastTransportState.PLAYING,
            positionMs = 0L,
            durationMs = media.durationMs,
            volume = volume,
        )
        startPolling(device)
        return stateFlow.value
    }

    suspend fun play() {
        val device = stateFlow.value.device ?: return
        runCatching { avController.play(device) }
            .onFailure { Logger.logInfo(TAG, "Cast play failed: ${it.message}") }
    }

    suspend fun pause() {
        val device = stateFlow.value.device ?: return
        runCatching { avController.pause(device) }
            .onFailure { Logger.logInfo(TAG, "Cast pause failed: ${it.message}") }
    }

    suspend fun seekTo(positionMs: Long) {
        val device = stateFlow.value.device ?: return
        runCatching { avController.seekTo(device, positionMs) }
            .onFailure { Logger.logInfo(TAG, "Cast seek failed: ${it.message}") }
    }

    suspend fun setVolume(value: Int) {
        val device = stateFlow.value.device ?: return
        renderingController.setVolume(device, value)
        stateFlow.value = stateFlow.value.copy(volume = value.coerceIn(0, 100))
    }

    /** Stops the remote session and tears down the local media server. */
    suspend fun stopCast(silent: Boolean = false) {
        pollJob?.cancel()
        pollJob = null
        val device = stateFlow.value.device
        if (device != null && !silent) {
            withContext(Dispatchers.IO) {
                runCatching { avController.stop(device) }
            }
        }
        server.stop()
        server.session = null
        volume = null
        stateFlow.value = CastState()
    }

    private fun startPolling(device: DlnaDevice) {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val transport = runCatching { avController.getTransportState(device) }.getOrNull()
                val position = runCatching { avController.getPositionInfo(device) }.getOrNull()
                if (transport == null && position == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        updateError(CastError(possiblyIncompatible = false, message = "与 ${device.name} 的连接不稳定"))
                        break
                    }
                    continue
                }
                consecutiveFailures = 0
                stateFlow.value = stateFlow.value.copy(
                    transportState = transport ?: stateFlow.value.transportState,
                    positionMs = position?.first ?: stateFlow.value.positionMs,
                    durationMs = position?.second ?: stateFlow.value.durationMs ?: stateFlow.value.media?.durationMs,
                )
            }
        }
    }

    private var consecutiveFailures = 0

    private fun updateError(error: CastError): CastState {
        val next = stateFlow.value.copy(error = error)
        stateFlow.value = next
        return next
    }

    fun clearError() {
        if (stateFlow.value.error != null) {
            stateFlow.value = stateFlow.value.copy(error = null)
        }
    }

    private fun openMediaDescriptor(uri: String): ParcelFileDescriptor? = runCatching {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "content" -> context.contentResolver.openFileDescriptor(parsed, "r")
            "file" -> ParcelFileDescriptor.open(File(parsed.path ?: return@runCatching null), ParcelFileDescriptor.MODE_READ_ONLY)
            else -> null
        }
    }.getOrNull()

    private fun buildDidl(media: CastMedia, url: String): String {
        val duration = media.durationMs?.let { """ duration="${UpnpTime.formatClock(it)}"""" } ?: ""
        val size = media.sizeBytes?.let { """ size="$it"""" } ?: ""
        return buildString {
            append("<DIDL-Lite ")
            append("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("""<item id="0" parentID="-1" restricted="1">""")
            append("<dc:title>").append(AvTransportController.escape(media.title)).append("</dc:title>")
            append("<upnp:class>object.item.videoItem</upnp:class>")
            append("<res").append(size).append(duration)
            append(""" protocolInfo="http-get:*:""")
            append(media.mime)
            append(":DLNA.ORG_OP=01;DLNA.ORG_CI=0\">")
            append(AvTransportController.escape(url))
            append("</res></item></DIDL-Lite>")
        }
    }

    private fun localAddress(): String? {
        advertisedHostOverride?.let { return it }
        return runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .map { it.hostAddress }
            .toList()
        // Prefer Wi-Fi clients (wlan*) which can reach TV renderers directly.
            candidates.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "CastSession"
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_CONSECUTIVE_FAILURES = 8

        /** AVTransport error codes that reliably point at rejected media. */
        val INCOMPATIBLE_FAULT_CODES = setOf(714, 715, 716, 717)
    }
}
