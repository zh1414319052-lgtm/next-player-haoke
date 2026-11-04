package dev.anilbeesetti.nextplayer.core.media.network.ftp

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpPlaybackStreamTest {
    @Test
    fun socketTimeoutResumesAfterBytesAlreadyDelivered() {
        val offsets = mutableListOf<Long>()
        val stream = FtpPlaybackStream(0) { offset ->
            offsets.add(offset)
            Transfer(if (offset == 0L) byteArrayOf(1, 2) else byteArrayOf(3, 4), 4, timeoutAtEnd = offset == 0L)
        }
        stream.use { assertArrayEquals(byteArrayOf(1, 2, 3, 4), it.readBytes()) }
        assertEquals(listOf(0L, 2L), offsets)
    }

    @Test
    fun offsetsBeyondTwoGigabytesAreNotTruncated() {
        val offset = 3_000_000_000L
        val offsets = mutableListOf<Long>()
        FtpPlaybackStream(offset) { position ->
            offsets.add(position)
            Transfer(byteArrayOf(5), offset + 2)
        }.use { assertArrayEquals(byteArrayOf(5, 5), it.readBytes()) }
        assertEquals(listOf(offset, offset + 1), offsets)
    }

    @Test
    fun changingFileSizeFailsInsteadOfCombiningDifferentContent() {
        val attempts = AtomicInteger()
        FtpPlaybackStream(0) {
            Transfer(byteArrayOf(1), if (attempts.getAndIncrement() == 0) 4 else 8)
        }.use { stream ->
            assertEquals(1, stream.read())
            assertThrows(IOException::class.java) { stream.read() }
        }
        assertEquals(4, attempts.get())
    }

    @Test
    fun successfulUnknownLengthEndsOnceWithoutReconnecting() {
        var opens = 0
        FtpPlaybackStream(0) { opens++; Transfer(byteArrayOf(7), -1) }.use {
            assertEquals(7, it.read())
            assertEquals(-1, it.read())
            assertEquals(-1, it.read())
            assertEquals(0, it.read(ByteArray(1), 0, 0))
        }
        assertEquals(1, opens)
    }

    @Test
    fun closeDuringBlockedReadDoesNotResurrectConnection() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var opens = 0
        val stream = FtpPlaybackStream(0) {
            opens++
            object : FtpTransfer {
                override val size = 20L
                override val input = object : InputStream() {
                    override fun read(): Int {
                        entered.countDown()
                        check(released.await(5, TimeUnit.SECONDS))
                        throw IOException("Closed")
                    }
                }
                override fun completed() = false
                override fun close() { released.countDown() }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<IOException> { assertThrows(IOException::class.java) { stream.read() } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stream.close()
            read.get(2, TimeUnit.SECONDS)
            assertEquals(1, opens)
            stream.close()
            assertThrows(IOException::class.java) { stream.read() }
        } finally {
            stream.close()
            executor.shutdownNow()
        }
    }

    private class Transfer(
        bytes: ByteArray,
        override val size: Long,
        private val timeoutAtEnd: Boolean = false,
    ) : FtpTransfer {
        override val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (timeoutAtEnd && available() == 0) throw SocketTimeoutException("Synthetic timeout")
                return super.read(b, off, len)
            }
        }
        override fun completed() = true
        override fun close() { input.close() }
    }
}
