package dev.anilbeesetti.nextplayer.core.cast.discovery

import dev.anilbeesetti.nextplayer.core.cast.model.DlnaDevice
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Parses a UPnP device description document into the fields needed to control a
 * DLNA MediaRenderer: friendly name, UDN, and absolute AVTransport and
 * RenderingControl control URLs (relative control URLs are resolved against the
 * document location, honouring an optional URLBase element).
 */
object DeviceDescriptionParser {

    fun parse(locationUrl: String, xml: String): DlnaDevice? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var friendlyName: String? = null
        var udn: String? = null
        var urlBase: String? = null
        var avTransportControl: String? = null
        var avTransportScpd: String? = null
        var renderingControlControl: String? = null
        var insideService = false
        var currentServiceType = ""
        var currentControlUrl = ""
        var currentScpdUrl = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "URLBase" -> urlBase = parser.nextText().trim().ifEmpty { null }
                    "service" -> insideService = true
                    "serviceType" -> if (insideService) currentServiceType = parser.nextText().trim()
                    "controlURL" -> if (insideService) currentControlUrl = parser.nextText().trim()
                    "SCPDURL" -> if (insideService) currentScpdUrl = parser.nextText().trim()
                    "friendlyName" -> if (friendlyName == null) friendlyName = parser.nextText().trim().ifEmpty { null }
                    "UDN" -> if (udn == null) udn = parser.nextText().trim().ifEmpty { null }
                }
                XmlPullParser.END_TAG -> if (parser.name == "service" && insideService) {
                    when {
                        currentServiceType.startsWith("urn:schemas-upnp-org:service:AVTransport:") -> {
                            avTransportControl = currentControlUrl.ifEmpty { null }
                            avTransportScpd = currentScpdUrl.ifEmpty { null }
                        }
                        currentServiceType.startsWith("urn:schemas-upnp-org:service:RenderingControl:") ->
                            renderingControlControl = currentControlUrl.ifEmpty { null }
                    }
                    insideService = false
                    currentServiceType = ""
                    currentControlUrl = ""
                    currentScpdUrl = ""
                }
            }
            parser.next()
        }

        val base = (urlBase ?: locationUrl).trimEnd('/')
        fun absolute(controlUrl: String?): String? {
            val control = controlUrl?.trim().orEmpty()
            if (control.isEmpty()) return null
            if (control.startsWith("http://") || control.startsWith("https://")) return control
            if (!control.startsWith("/")) return "$base/$control"
            // Keep scheme and authority from the base, append the control path.
            val schemeEnd = base.indexOf("://")
            val authorityEnd = base.indexOf('/', schemeEnd + 3)
            val origin = if (authorityEnd == -1) base else base.substring(0, authorityEnd)
            return origin + control
        }

        val transport = absolute(avTransportControl) ?: return null
        val name = friendlyName ?: udn ?: "DLNA 设备"
        return DlnaDevice(
            name = name,
            udn = udn ?: locationUrl,
            locationUrl = locationUrl,
            avTransportUrl = transport,
            avTransportScpdUrl = absolute(avTransportScpd),
            renderingControlUrl = absolute(renderingControlControl),
        )
    }

    /**
     * Extracts the sink protocol entries, e.g. `http-get:*:video/mp4:*`. Some
     * devices put them in a <Sink> element, others in the SinkProtocolInfo
     * state variable default; both shapes are accepted.
     */
    fun parseProtocolInfo(xml: String): Set<String> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Sink", "defaultValue" -> {
                        val raw = parser.nextText()
                        val entries = raw.split(",").map { it.trim() }.filter { entry ->
                            entry.startsWith("http-get:", ignoreCase = true)
                        }
                        if (entries.isNotEmpty()) return entries.toSet()
                    }
                }
            }
            parser.next()
        }
        return emptySet()
    }
}
