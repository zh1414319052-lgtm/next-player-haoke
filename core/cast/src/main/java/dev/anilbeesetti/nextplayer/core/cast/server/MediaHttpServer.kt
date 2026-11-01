package dev.anilbeesetti.nextplayer.core.cast.server

import android.os.ParcelFileDescriptor
import dev.anilbeesetti.nextplayer.core.common.Logger
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves exactly one whitelisted media file to the local network while a cast
 * session is active. Implements the small HTTP subset TV players actually use:
 * GET/HEAD, a single byte range, and connection-per-request with `Connection:
 * close`. Written by hand instead of pulling a general HTTP framework so that
 * range semantics, descriptor lifetimes and access gating stay explicit.
 */
class MediaHttpServer {

    class Session(
        val token: String,
        val mime: String,
        val lengthBytes: Long,
        val openDescriptor: () -> ParcelFileDescriptor?,
    )

    private var serverSocket: ServerSocket? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)

    @Volatile
    var session: Session? = null

    /** Binds [preferredPort] (or an ephemeral fallback) and returns the bound port. */
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        check(!running.get()) { "media server already running" }
        val socket = try {
            ServerSocket(preferredPort).apply { reuseAddress = true }
        } catch (_: java.net.BindException) {
            ServerSocket(0).apply { reuseAddress = true }
        }.apply {
            reuseAddress = true
            soTimeout = ACCEPT_POLL_MS
        }
        serverSocket = socket
        running.set(true)
        executor.execute {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    if (running.get()) continue else break
                }
                executor.execute { handle(client) }
            }
        }
        val port = socket.localPort
        Logger.logInfo(TAG, "Media server listening on port $port")
        return port
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        Logger.logInfo(TAG, "Media server stopped")
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = IO_TIMEOUT_MS
            socket.use { client ->
                val request = readRequest(client.getInputStream()) ?: return
                val current = session
                val path = request.path
                val expected = current?.let { "/media/${it.token}" }
                if (current == null || path != expected) {
                    respond(client, 404, "text/plain", "not found".toByteArray(), range = null, headOnly = request.headOnly, total = null)
                    return
                }
                val range = parseRange(request.rangeHeader, current.lengthBytes)
                if (request.rangeHeader != null && range == null) {
                    respond(client, 416, "text/plain", "invalid range".toByteArray(), range = null, headOnly = request.headOnly, total = current.lengthBytes)
                    return
                }
                val descriptor = current.openDescriptor() ?: run {
                    respond(client, 503, "text/plain", "media unavailable".toByteArray(), range = null, headOnly = request.headOnly, total = null)
                    return
                }
                try {
                    val stream = descriptorStream(descriptor)
                    stream.use { input ->
                        val start = range?.first ?: 0L
                        if (start > 0) skipFully(input, start)
                        val length = range?.let { (first: Long, last: Long) -> last - first + 1 } ?: current.lengthBytes
                        val status = if (range == null) 200 else 206
                        val header = buildString {
                            append("HTTP/1.1 ").append(status).append(if (status == 206) " Partial Content" else " OK").append("\r\n")
                            append("Content-Type: ").append(current.mime).append("\r\n")
                            append("Content-Length: ").append(length).append("\r\n")
                            append("Accept-Ranges: bytes\r\n")
                            if (range != null) {
                                append("Content-Range: bytes ").append(range.first).append('-').append((range.second))
                                    .append('/').append(current.lengthBytes).append("\r\n")
                            }
                            append("Connection: close\r\n\r\n")
                        }
                        client.getOutputStream().apply {
                            write(header.toByteArray(Charsets.ISO_8859_1))
                            if (!request.headOnly) {
                                var remaining = length
                                val buffer = ByteArray(COPY_BUFFER_BYTES)
                                while (remaining > 0) {
                                    val chunk = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (chunk == -1) break
                                    write(buffer, 0, chunk)
                                    remaining -= chunk
                                }
                            }
                            flush()
                        }
                    }
                    Logger.logInfo(TAG, "Served ${request.method} ${request.rangeHeader ?: "full"} len=${current.lengthBytes}")
                } finally {
                    runCatching { descriptor.close() }
                }
            }
        } catch (error: Exception) {
            Logger.logInfo(TAG, "Media request ended: ${error.message}")
        }
    }

    private fun descriptorStream(descriptor: ParcelFileDescriptor): InputStream {
        // Auto-close the descriptor together with the stream that consumes it.
        return object : FilterInputStream(BufferedInputStream(FileInputStream(descriptor.fileDescriptor))) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { descriptor.close() }
                }
            }
        }
    }

    private data class HttpRequest(val method: String, val path: String, val rangeHeader: String?) {
        val headOnly: Boolean get() = method == "HEAD"
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val head = StringBuilder()
        val buffer = ByteArray(1)
        while (head.length < MAX_HEADER_BYTES) {
            val read = input.read(buffer)
            if (read == -1) return null
            head.append(buffer[0].toInt().toChar())
            if (head.endsWith("\r\n\r\n")) break
        }
        val lines = head.toString().split("\r\n").filter { it.isNotBlank() }
        val requestLine = lines.firstOrNull()?.split(" ") ?: return null
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val path = requestLine[1]
        val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        return HttpRequest(method = method, path = path, rangeHeader = range)
    }

    /** Parses `bytes=first-last` into an inclusive closed interval, or null when invalid. */
    internal fun parseRange(header: String?, totalLength: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank() || totalLength <= 0) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").find(header.trim()) ?: return null
        val firstText = match.groupValues[1]
        val lastText = match.groupValues[2]
        if (firstText.isEmpty() && lastText.isEmpty()) return null
        val (first, last) = when {
            firstText.isEmpty() -> {
                // Suffix range: last N bytes.
                val suffix = lastText.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                val count = minOf(suffix, totalLength)
                (totalLength - count) to (totalLength - 1)
            }
            else -> {
                val first = firstText.toLongOrNull() ?: return null
                val last = lastText.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
                if (first > last || first >= totalLength) return null
                first to last
            }
        }
        return first to last
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() == -1) return
                remaining -= 1
            }
        }
    }

    private fun respond(socket: Socket, status: Int, mime: String, body: ByteArray, range: Pair<Long, Long>?, headOnly: Boolean, total: Long?) {
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            else -> "Service Unavailable"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(mime).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            if (status == 416 && total != null) append("Content-Range: bytes */").append(total).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            if (!headOnly) write(body)
            flush()
        }
    }

    private companion object {
        const val TAG = "CastServer"
        const val DEFAULT_PORT = 39893
        const val ACCEPT_POLL_MS = 500
        const val IO_TIMEOUT_MS = 15_000
        const val MAX_HEADER_BYTES = 8 * 1024
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
