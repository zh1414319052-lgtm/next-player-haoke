package dev.anilbeesetti.nextplayer.core.cast.control

import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** RenderingControl 1 subset: master volume read and set. */
class RenderingControlController {

    suspend fun getVolume(device: DlnaDevice): Int? = withContext(Dispatchers.IO) {
        if (device.renderingControlUrl.isNullOrBlank()) return@withContext null
        runCatching {
            val body = postAction(device.renderingControlUrl, "GetVolume") {
                append("<InstanceID>0</InstanceID><Channel>Master</Channel>")
            }
            AvTransportController.argument(body, "CurrentVolume").toIntOrNull()?.coerceIn(0, 100)
        }.getOrNull()
    }

    suspend fun setVolume(device: DlnaDevice, volume: Int) = withContext(Dispatchers.IO) {
        if (device.renderingControlUrl.isNullOrBlank()) return@withContext
        val target = volume.coerceIn(0, 100)
        runCatching {
            postAction(device.renderingControlUrl, "SetVolume") {
                append("<InstanceID>0</InstanceID><Channel>Master</Channel>")
                append("<DesiredVolume>").append(target).append("</DesiredVolume>")
            }
        }
    }

    private fun postAction(controlUrl: String, action: String, arguments: StringBuilder.() -> Unit): String {
        val serviceType = "urn:schemas-upnp-org:service:RenderingControl:1"
        val soap = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
            arguments(this)
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }
        val connection = java.net.URL(controlUrl).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            connection.outputStream.use { it.write(soap.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                throw java.io.IOException("RenderingControl $action -> HTTP ${connection.responseCode}")
            }
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
