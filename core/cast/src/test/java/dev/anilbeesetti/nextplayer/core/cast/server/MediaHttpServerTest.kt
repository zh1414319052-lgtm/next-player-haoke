package dev.anilbeesetti.nextplayer.core.cast.server

import android.os.ParcelFileDescriptor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaHttpServerTest {
    private lateinit var file: File
    private lateinit var content: ByteArray
    private lateinit var server: MediaHttpServer
    private var port = 0

    @Before
    fun setUp() {
        content = ByteArray(1024 * 1024) { Random(it).nextInt().toByte() }
        file = File.createTempFile("cast-media", ".mkv")
        file.outputStream().use { it.write(content) }
        server = MediaHttpServer()
        server.session = MediaHttpServer.Session(
            token = "testtoken",
            mime = "video/x-matroska",
            lengthBytes = content.size.toLong(),
            openDescriptor = { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) },
        )
        port = server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        file.delete()
    }

    private fun connect(request: String): HttpURLConnection =
        (URL("http://127.0.0.1:$port$request").openConnection() as HttpURLConnection)

    @Test
    fun servesFullBodyWithAcceptRangesAnd404sUnknownTokens() {
        val connection = connect("/media/testtoken")
        connection.requestMethod = "GET"
        assertEquals(200, connection.responseCode)
        assertEquals(content.size.toLong(), connection.contentLengthLong)
        assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
        assertArrayEquals(content, connection.inputStream.use { it.readBytes() })

        val wrongToken = connect("/media/othertoken")
        assertEquals(404, wrongToken.responseCode)
        wrongToken.disconnect()
    }

    @Test
    fun servesSingleByteRangesWithContentRange() {
        val connection = connect("/media/testtoken")
        connection.setRequestProperty("Range", "bytes=100-199")
        assertEquals(206, connection.responseCode)
        assertEquals(100L, connection.contentLengthLong)
        assertEquals("bytes 100-199/${content.size}", connection.getHeaderField("Content-Range"))
        val body = connection.inputStream.use { it.readBytes() }
        assertArrayEquals(content.copyOfRange(100, 200), body)
        connection.disconnect()

        // Suffix range: last 10 bytes.
        val suffix = connect("/media/testtoken")
        suffix.setRequestProperty("Range", "bytes=-10")
        assertEquals(206, suffix.responseCode)
        assertArrayEquals(content.copyOfRange(content.size - 10, content.size), suffix.inputStream.use { it.readBytes() })
        suffix.disconnect()

        // Out-of-bounds start.
        val invalid = connect("/media/testtoken")
        invalid.setRequestProperty("Range", "bytes=${content.size + 10}-")
        assertEquals(416, invalid.responseCode)
        assertEquals("bytes */${content.size}", invalid.getHeaderField("Content-Range"))
        invalid.disconnect()
    }

    @Test
    fun headRequestsSendHeadersWithoutBody() {
        val connection = connect("/media/testtoken")
        connection.requestMethod = "HEAD"
        assertEquals(200, connection.responseCode)
        assertEquals(content.size.toLong(), connection.contentLengthLong)
        assertEquals(0, connection.inputStream.use { it.readBytes() }.size)
    }

    @Test
    fun rangeParserFollowsHttpSemantics() {
        assertEquals(100L to 199L, server.parseRange("bytes=100-199", 1_000))
        assertEquals(900L to 999L, server.parseRange("bytes=900-", 1_000))
        assertEquals(990L to 999L, server.parseRange("bytes=-10", 1_000))
        assertEquals(900L to 999L, server.parseRange("bytes=900-2000", 1_000))
        assertNull(server.parseRange(null, 1_000))
        assertNull(server.parseRange("bytes=-", 1_000))
        assertNull(server.parseRange("bytes=1000-2000", 1_000))
        assertNull(server.parseRange("bytes=500-400", 1_000))
    }
}
