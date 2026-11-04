package dev.anilbeesetti.nextplayer.core.media.network.clients

import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real control/data sockets: a server-side timeout must never masquerade as a movie's EOF. */
class FtpClientTest {
    @Test
    fun interruptedTransferResumesAtExactByteWithoutTruncation() = runBlocking {
        TestFtpServer(failures = 1).use { server ->
            val actual = server.client().openStream("/movie.mp4").use { it.readBytes() }
            assertArrayEquals(server.bytes, actual)
            assertEquals(listOf(0L, 8192L), server.offsets)
        }
    }

    @Test
    fun unknownSizeUsesCompletionReplyToDistinguishFailureFromEof() = runBlocking {
        TestFtpServer(failures = 1, knownSize = false).use { server ->
            assertArrayEquals(server.bytes, server.client().openStream("/movie.mp4").use { it.readBytes() })
            assertEquals(listOf(0L, 8192L), server.offsets)
        }
    }

    @Test
    fun normalEndAndSeekPreserveExactBytes() = runBlocking {
        TestFtpServer().use { server ->
            val client = server.client()
            assertArrayEquals(server.bytes.copyOfRange(12345, server.bytes.size), client.openStream("/movie.mp4", 12345).use { it.readBytes() })
            assertEquals(listOf(12345L), server.offsets)
            assertArrayEquals(server.bytes, client.openStream("/movie.mp4").use { it.readBytes() })
        }
    }

    @Test
    fun repeatedFailureIsBoundedAndReportedInsteadOfEof() {
        TestFtpServer(failures = Int.MAX_VALUE, failAfter = 0).use { server ->
            assertThrows(IOException::class.java) {
                runBlocking { server.client().openStream("/movie.mp4").use { it.readBytes() } }
            }
            assertEquals(4, server.offsets.size)
        }
    }

    @Test
    fun unsupportedRestartNeverSilentlyReplaysBeginning() {
        TestFtpServer(failures = 1, allowRestart = false).use { server ->
            assertThrows(IOException::class.java) {
                runBlocking { server.client().openStream("/movie.mp4").use { it.readBytes() } }
            }
            assertEquals(listOf(0L), server.offsets)
        }
    }

    @Test
    fun closeDoesNotWaitForAnUnresponsiveCompletionReply() = runBlocking {
        TestFtpServer(withholdCompletion = true).use { server ->
            val stream = server.client().openStream("/movie.mp4")
            assertTrue(stream.read() >= 0)
            val started = System.nanoTime()
            stream.close()
            assertTrue("Closing a discarded FTP session must not wait for 226/QUIT", (System.nanoTime() - started) / 1_000_000 < 1_000)
        }
    }

    @Test
    fun expiredMetadataConnectionIsRefreshedForSeekingAndBrowsing() = runBlocking {
        TestFtpServer().use { server ->
            val client = server.client()
            client.connect().getOrThrow()
            try {
                assertEquals(server.bytes.size.toLong(), client.fileSize("/movie.mp4"))
                server.expireConnections()
                assertEquals(server.bytes.size.toLong(), client.fileSize("/movie.mp4"))
                server.expireConnections()
                assertTrue(client.listFiles("/").isSuccess)
            } finally {
                client.disconnect()
            }
        }
    }

    @Test
    fun closingOneStreamDoesNotDisconnectAnotherStream() = runBlocking {
        TestFtpServer().use { server ->
            val client = server.client()
            val first = client.openStream("/movie.mp4")
            val second = client.openStream("/movie.mp4", 5678)
            first.close()
            second.use { assertArrayEquals(server.bytes.copyOfRange(5678, server.bytes.size), it.readBytes()) }
        }
    }

    private class TestFtpServer(
        private val failures: Int = 0,
        private val failAfter: Int = 8192,
        private val knownSize: Boolean = true,
        private val allowRestart: Boolean = true,
        private val withholdCompletion: Boolean = false,
    ) : Closeable {
        val bytes = ByteArray(128 * 1024) { (it * 31).toByte() }
        val offsets: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        private val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val sockets: MutableList<Socket> = Collections.synchronizedList(mutableListOf())
        private val pool = Executors.newCachedThreadPool { Thread(it, "test-ftp").apply { isDaemon = true } }
        private val attempts = AtomicInteger()

        init {
            pool.execute {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: IOException) { break }
                    sockets.add(socket)
                    pool.execute { runCatching { serve(socket) } }
                }
            }
        }

        fun client() = FtpClient(NetworkConnection(name = "test", protocol = NetworkProtocol.FTP, host = "127.0.0.1", port = listener.localPort))

        fun expireConnections() {
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        }

        private fun serve(socket: Socket) {
            var passive: ServerSocket? = null
            try {
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                fun reply(text: String) { writer.write("$text\r\n"); writer.flush() }
                var offset = 0L
                reply("220 Test FTP")
                while (true) {
                    val line = reader.readLine() ?: break
                    val argument = line.substringAfter(' ', "")
                    when (line.substringBefore(' ')) {
                        "USER" -> reply("331 Password")
                        "PASS" -> reply("230 Logged in")
                        "TYPE", "OPTS", "NOOP" -> reply("200 OK")
                        "SIZE" -> reply(if (knownSize) "213 ${bytes.size}" else "502 Unsupported")
                        "SYST" -> reply("215 UNIX Type: L8")
                        "PASV" -> {
                            passive?.close()
                            passive = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                            val port = passive.localPort
                            reply("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
                        }
                        "REST" -> if (allowRestart) {
                            offset = argument.toLong()
                            reply("350 Restart accepted")
                        } else {
                            reply("502 REST unsupported")
                        }
                        "RETR", "LIST" -> {
                            reply("150 Opening data")
                            val broken = line.startsWith("RETR") && attempts.getAndIncrement() < failures
                            passive!!.accept().use { data ->
                                if (line.startsWith("RETR")) {
                                    offsets.add(offset)
                                    val count = if (broken) minOf(failAfter, bytes.size - offset.toInt()) else bytes.size - offset.toInt()
                                    data.getOutputStream().write(bytes, offset.toInt(), count)
                                }
                            }
                            passive.close()
                            passive = null
                            offset = 0
                            if (!withholdCompletion) reply(if (broken) "426 Data connection timed out" else "226 Transfer complete")
                        }
                        "QUIT" -> { reply("221 Bye"); return }
                        else -> reply("502 Unsupported")
                    }
                }
            } finally {
                passive?.close()
                socket.close()
            }
        }

        override fun close() {
            listener.close()
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            pool.shutdownNow()
        }
    }
}
