package dev.anilbeesetti.nextplayer.core.cast.control

import dev.anilbeesetti.nextplayer.core.cast.model.CastTransportState
import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import dev.anilbeesetti.nextplayer.core.cast.util.UpnpTime
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A DLNA control call that failed, with the renderer's error code when present. */
class UpnpControlException(
    val errorCode: Int?,
    message: String,
) : Exception(message)

/**
 * Minimal AVTransport 1 control point: SetAVTransportURI, Play, Pause, Stop,
 * Seek, GetPositionInfo and GetTransportInfo, spoken as SOAP over HTTP POST.
 */
class AvTransportController(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
) {

    suspend fun setAvTransportUri(device: DlnaDevice, uri: String, metadataXml: String) = withContext(Dispatchers.IO) {
        postAction(device.avTransportUrl, ACTION_SET_URI, SERVICE_TYPE) {
            append("<InstanceID>0</InstanceID>")
            append("<CurrentURI>").append(escape(uri)).append("</CurrentURI>")
            append("<CurrentURIMetaData>").append(escape(metadataXml)).append("</CurrentURIMetaData>")
        }
        Unit
    }

    suspend fun play(device: DlnaDevice) = withContext(Dispatchers.IO) {
        postAction(device.avTransportUrl, ACTION_PLAY, SERVICE_TYPE) {
            append("<InstanceID>0</InstanceID><Speed>1</Speed>")
        }
        Unit
    }

    suspend fun pause(device: DlnaDevice) = withContext(Dispatchers.IO) {
        postAction(device.avTransportUrl, ACTION_PAUSE, SERVICE_TYPE) {
            append("<InstanceID>0</InstanceID>")
        }
        Unit
    }

    suspend fun stop(device: DlnaDevice) = withContext(Dispatchers.IO) {
        postAction(device.avTransportUrl, ACTION_STOP, SERVICE_TYPE) {
            append("<InstanceID>0</InstanceID>")
        }
        Unit
    }

    suspend fun seekTo(device: DlnaDevice, positionMs: Long) = withContext(Dispatchers.IO) {
        postAction(device.avTransportUrl, ACTION_SEEK, SERVICE_TYPE) {
            append("<InstanceID>0</InstanceID>")
            append("<Unit>REL_TIME</Unit>")
            append("<Target>").append(UpnpTime.formatClock(positionMs)).append("</Target>")
        }
        Unit
    }

    /** Returns (positionMs, durationMs); either may be null when the renderer withholds it. */
    suspend fun getPositionInfo(device: DlnaDevice): Pair<Long?, Long?> = withContext(Dispatchers.IO) {
        val body = postAction(device.avTransportUrl, ACTION_GET_POSITION_INFO, SERVICE_TYPE) {
            append("<InstanceID>0</InstanceID>")
        }
        val position = UpnpTime.parseClockOrNull(argument(body, "RelTime"))
        val duration = UpnpTime.parseClockOrNull(argument(body, "TrackDuration"))
        position to duration
    }

    suspend fun getTransportState(device: DlnaDevice): CastTransportState = withContext(Dispatchers.IO) {
        val body = postAction(device.avTransportUrl, ACTION_GET_TRANSPORT_INFO, SERVICE_TYPE) {
            append("<InstanceID>0</InstanceID>")
        }
        when (argument(body, "CurrentTransportState").uppercase()) {
            "PLAYING" -> CastTransportState.PLAYING
            "PAUSED_PLAYBACK" -> CastTransportState.PAUSED
            "STOPPED" -> CastTransportState.STOPPED
            "TRANSITIONING" -> CastTransportState.TRANSITIONING
            "NO_MEDIA_PRESENT" -> CastTransportState.NO_MEDIA
            else -> CastTransportState.UNKNOWN
        }
    }

    private fun postAction(
        controlUrl: String,
        action: String,
        serviceType: String,
        arguments: StringBuilder.() -> Unit,
    ): String {
        val soap = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
            arguments(this)
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }
        val connection = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            connection.outputStream.use { it.write(soap.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                val fault = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                throw UpnpControlException(
                    errorCode = fault?.let(::parseFaultCode),
                    message = fault?.let(::parseFaultDescription) ?: "HTTP ${connection.responseCode} for $action",
                )
            }
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
        const val ACTION_SET_URI = "SetAVTransportURI"
        const val ACTION_PLAY = "Play"
        const val ACTION_PAUSE = "Pause"
        const val ACTION_STOP = "Stop"
        const val ACTION_SEEK = "Seek"
        const val ACTION_GET_POSITION_INFO = "GetPositionInfo"
        const val ACTION_GET_TRANSPORT_INFO = "GetTransportInfo"

        fun escape(value: String): String = buildString(value.length) {
            for (ch in value) when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }

        /** Pulls a text element out of a SOAP response body; namespaces are ignored. */
        fun argument(body: String, element: String): String {
            val open = "<$element>"
            val close = "</$element>"
            val start = body.indexOf(open)
            if (start == -1) return ""
            val end = body.indexOf(close, start)
            if (end == -1) return ""
            return body.substring(start + open.length, end).trim()
        }

        fun parseFaultCode(faultXml: String): Int? = argument(faultXml, "errorCode").toIntOrNull()

        fun parseFaultDescription(faultXml: String): String {
            argument(faultXml, "faultstring").takeIf { it.isNotEmpty() }?.let { return it }
            argument(faultXml, "errorDescription").takeIf { it.isNotEmpty() }?.let { return it }
            return "UPnP error"
        }
    }
}
