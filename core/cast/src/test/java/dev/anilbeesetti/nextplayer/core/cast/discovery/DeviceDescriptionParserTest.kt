package dev.anilbeesetti.nextplayer.core.cast.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceDescriptionParserTest {

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <URLBase>http://192.168.31.20:49152/</URLBase>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>客厅电视</friendlyName>
            <UDN>uuid:16d2af4e-tv-1</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <SCPDURL>/RenderingControl.xml</SCPDURL>
                <controlURL>/RenderingControl/control</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <SCPDURL>AVTransport.xml</SCPDURL>
                <controlURL>/AVTransport/control</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun resolvesRelativeControlUrlsAgainstUrlBase() {
        val device = DeviceDescriptionParser.parse("http://192.168.31.20:49152/description.xml", description)

        assertEquals("客厅电视", device!!.name)
        assertEquals("uuid:16d2af4e-tv-1", device.udn)
        assertEquals("http://192.168.31.20:49152/AVTransport/control", device.avTransportUrl)
        assertEquals("http://192.168.31.20:49152/AVTransport.xml", device.avTransportScpdUrl)
        assertEquals("http://192.168.31.20:49152/RenderingControl/control", device.renderingControlUrl)
    }

    @Test
    fun keepsAbsoluteControlUrlsAndFallsBackToLocationBase() {
        val xml = description
            .replace("<URLBase>http://192.168.31.20:49152/</URLBase>", "")
            .replace("<controlURL>/AVTransport/control</controlURL>", "<controlURL>http://10.0.0.5:8080/avt</controlURL>")
        val device = DeviceDescriptionParser.parse("http://192.168.31.20:49152/dd.xml", xml)

        assertEquals("http://10.0.0.5:8080/avt", device!!.avTransportUrl)
        assertEquals("http://192.168.31.20:49152/RenderingControl/control", device.renderingControlUrl)
    }

    @Test
    fun devicesWithoutAvTransportAreNotRenderers() {
        val xml = description.replace(Regex("<service>.*</service>", RegexOption.DOT_MATCHES_ALL), "<service></service>")
        assertNull(DeviceDescriptionParser.parse("http://x/dd.xml", xml))
    }

    @Test
    fun parsesSinkProtocolInfo() {
        val scpd = """
            <?xml version="1.0"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <actionList/>
              <serviceStateTable>
                <stateVariable>
                  <name>SinkProtocolInfo</name>
                  <dataType>string</dataType>
                  <defaultValue>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:audio/mp4:*</defaultValue>
                </stateVariable>
              </serviceStateTable>
            </scpd>
        """.trimIndent()
        assertEquals(
            setOf("http-get:*:video/mp4:*", "http-get:*:video/x-matroska:*", "http-get:*:audio/mp4:*"),
            DeviceDescriptionParser.parseProtocolInfo(scpd),
        )
    }
}
