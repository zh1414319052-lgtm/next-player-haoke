package dev.anilbeesetti.nextplayer.core.media.network.ftp

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** One dedicated FTP session. The caller discards it instead of reusing pending replies. */
internal interface FtpTransfer : AutoCloseable {
    val input: InputStream
    val size: Long
    fun completed(): Boolean
}

/**
 * FTP data sockets can close cleanly when a server/router expires a session. Validate EOF and
 * resume using REST at the last byte actually delivered, without restarting the media timeline.
 * Memory use is constant; three reconnects without progress are the limit. No background worker
 * or persistent keep-alive is needed, including while playback is paused.
 */
internal class FtpPlaybackStream(
    offset: Long,
    private val openTransfer: (Long) -> FtpTransfer,
) : InputStream() {
    private val lock = Any()
    @Volatile
    private var closed = false
    private var transfer: FtpTransfer? = openTransfer(offset)
    private var position = offset
    private var expectedSize = transfer!!.size
    private var ended = false
    private var failure: IOException? = null
    private val singleByte = ByteArray(1)

    override fun read(): Int = if (read(singleByte, 0, 1) < 0) -1 else singleByte[0].toInt() and 0xff

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        ensureOpen()
        if (length == 0) return 0
        failure?.let { throw it }
        if (ended || (expectedSize >= 0 && position == expectedSize)) return -1

        var reconnects = 0
        while (true) {
            ensureOpen()
            try {
                val current = transfer ?: openAndInstallTransfer()
                val readLength = if (expectedSize >= 0) minOf(length.toLong(), expectedSize - position).toInt() else length
                val count = current.input.read(buffer, offset, readLength)
                ensureOpen()
                if (count >= 0) {
                    position += count
                    return count
                }
                if (expectedSize >= 0 && position < expectedSize) throw EOFException("FTP transfer ended before the file was fully read")
                if (!current.completed()) throw EOFException("FTP transfer did not complete successfully")
                ended = true
                discardTransfer()
                return -1
            } catch (error: IOException) {
                discardTransfer()
                if (closed || Thread.currentThread().isInterrupted || (error is InterruptedIOException && error !is SocketTimeoutException)) throw error
                if (reconnects++ >= MAX_RECONNECTS) {
                    failure = error
                    throw error
                }
                try {
                    Thread.sleep(250L * reconnects)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("FTP read interrupted").apply { initCause(interrupted) }
                }
            }
        }
    }

    private fun openAndInstallTransfer(): FtpTransfer {
        val fresh = openTransfer(position)
        synchronized(lock) {
            if (closed) {
                fresh.close()
                throw IOException("FTP stream is closed")
            }
            if (expectedSize >= 0 && fresh.size >= 0 && fresh.size != expectedSize) {
                fresh.close()
                throw IOException("FTP file size changed during playback")
            }
            if (expectedSize < 0) expectedSize = fresh.size
            if (expectedSize >= 0 && position > expectedSize) {
                fresh.close()
                throw EOFException("FTP restart position is beyond the file")
            }
            transfer = fresh
        }
        return fresh
    }

    private fun ensureOpen() {
        if (closed) throw IOException("FTP stream is closed")
    }

    private fun discardTransfer() {
        val previous = synchronized(lock) { transfer.also { transfer = null } }
        previous?.close()
    }

    override fun close() {
        closed = true
        discardTransfer()
    }

    private companion object {
        const val MAX_RECONNECTS = 3
    }
}
