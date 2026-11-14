package dev.anilbeesetti.nextplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anilbeesetti.nextplayer.core.cast.CastSessionManager
import dev.anilbeesetti.nextplayer.core.cast.control.AvTransportController
import dev.anilbeesetti.nextplayer.core.cast.discovery.DlnaDiscovery
import dev.anilbeesetti.nextplayer.core.cast.model.CastMedia
import dev.anilbeesetti.nextplayer.core.cast.model.CastTransportState
import dev.anilbeesetti.nextplayer.core.cast.model.discoveryKey
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTP/SOAP and media reads on Android, with a synthetic renderer (no TV decoder claim). */
@RunWith(AndroidJUnit4::class)
class CastLoopbackDeviceTest {
    @Test
    fun realDiscoveryCanBeClosedAndReopenedWithoutLeakingItsSocket() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(2) {
            val scan = launch {
                DlnaDiscovery(context).discover().collect { devices ->
                    assertEquals(devices.size, devices.map { it.discoveryKey }.distinct().size)
                }
            }
            delay(500)
            scan.cancelAndJoin()
        }
    }

    @Test
    fun descriptionStreamingPauseSeekResumeAndStopWorkAfterSelectingRenderer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = CastSessionManager(context, scope).apply { advertisedHostOverride = "127.0.0.1" }
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val file = File(context.cacheDir, "cast-regression-synthetic.bin").apply { writeBytes(payload) }
        Renderer().use { renderer ->
            try {
                val device = manager.describeDevice("${renderer.origin}/dd.xml")
                assertNotNull(device)
                val result = manager.startCast(device!!, CastMedia("Synthetic fixture", file.toURI().toString(), "video/mp4", file.length(), 60_000))
                assertEquals(CastTransportState.PLAYING, result.transportState)
                assertArrayEquals(payload.copyOfRange(0, 128), renderer.mediaBytes)
                manager.pause()
                await { manager.state.value.transportState == CastTransportState.PAUSED }
                manager.seekTo(12_000)
                await { manager.state.value.positionMs == 12_000L }
                manager.play()
                await { manager.state.value.transportState == CastTransportState.PLAYING }
                manager.stopCast()
                assertFalse(manager.state.value.isActive)
                assertEquals("STOPPED", renderer.transport)
            } finally {
                manager.stopCast()
                scope.cancel()
            }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(8_000) {
        while (!condition()) delay(100)
    }

    private class Renderer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val origin = "http://127.0.0.1:${socket.localPort}"

        @Volatile var transport = "STOPPED"

        @Volatile var mediaBytes: ByteArray? = null
        private var mediaUri = ""
        private var position = "00:00:00"

        init {
            executor.submit {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: java.net.SocketException) {
                        break
                    }
                    client.use {
                        it.soTimeout = 5_000
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < chars.size) {
                            val count = reader.read(chars, offset, chars.size - offset)
                            if (count < 0) break
                            offset += count
                        }
                        val body = String(chars)
                        val action = headers["soapaction"]?.substringAfter('#')?.trim('"').orEmpty()
                        val response = if (request.startsWith("GET /dd.xml")) {
                            """<root><device><friendlyName>Synthetic renderer</friendlyName><UDN>uuid:cast-regression</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>$origin/control</controlURL></service></serviceList></device></root>"""
                        } else {
                            when (action) {
                                "SetAVTransportURI" -> mediaUri = AvTransportController.argument(body, "CurrentURI")
                                "Play" -> {
                                    val connection = URL(mediaUri).openConnection() as HttpURLConnection
                                    connection.setRequestProperty("Range", "bytes=0-127")
                                    connection.connectTimeout = 3_000
                                    connection.readTimeout = 3_000
                                    try {
                                        mediaBytes = connection.inputStream.use { stream -> stream.readBytes() }
                                    } finally {
                                        connection.disconnect()
                                    }
                                    transport = "PLAYING"
                                }
                                "Pause" -> transport = "PAUSED_PLAYBACK"
                                "Stop" -> transport = "STOPPED"
                                "Seek" -> position = AvTransportController.argument(body, "Target")
                            }
                            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><CurrentTransportState>$transport</CurrentTransportState><RelTime>$position</RelTime><TrackDuration>00:01:00</TrackDuration></s:Body></s:Envelope>"""
                        }.toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(response)
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            executor.shutdownNow()
        }
    }
}
