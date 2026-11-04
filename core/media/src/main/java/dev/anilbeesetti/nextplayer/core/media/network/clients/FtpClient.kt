package dev.anilbeesetti.nextplayer.core.media.network.clients

import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.media.network.ftp.FtpPlaybackStream
import dev.anilbeesetti.nextplayer.core.media.network.ftp.FtpTransfer
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * FTP client backed by Apache commons-net. Browse paths are absolute paths on the server.
 * Streaming uses a dedicated control connection with `REST` (restart offset) for seeking.
 */
class FtpClient(private val connection: NetworkConnection) : NetworkClient {

    private var ftpClient: FTPClient? = null
    private val controlMutex = Mutex()

    override val rootPath: String = connection.path
        .trim()
        .trim('/')
        .let { path -> if (path.isEmpty()) "/" else "/$path" }

    private fun newClient(): FTPClient = FTPClient().apply {
        controlEncoding = "UTF-8"
        connectTimeout = 15_000
        dataTimeout = 15.seconds.toJavaDuration()
        defaultTimeout = 15_000
    }

    private fun FTPClient.loginAndPrepare(): Boolean {
        connect(connection.host, connection.effectivePort)
        if (!FTPReply.isPositiveCompletion(replyCode)) {
            disconnect()
            return false
        }
        val ok = if (connection.isAnonymous) login("anonymous", "") else login(connection.username, connection.password)
        if (!ok) {
            disconnect()
            return false
        }
        if (!setFileType(FTP.BINARY_FILE_TYPE)) throw IOException("FTP binary transfer mode was rejected")
        enterLocalPassiveMode()
        sendCommand("OPTS UTF8 ON")
        bufferSize = 64 * 1024
        return true
    }

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            runCatching {
                val client = newClient()
                try {
                    if (!client.loginAndPrepare()) throw IOException("FTP login failed")
                    ftpClient?.let { runCatching { it.disconnect() } }
                    ftpClient = client
                } catch (error: Throwable) {
                    runCatching { if (client.isConnected) client.disconnect() }
                    throw error
                }
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            ftpClient?.let { client -> runCatching { client.disconnect() } }
            ftpClient = null
        }
    }

    override fun isConnected(): Boolean = ftpClient?.isConnected == true

    override suspend fun listFiles(path: String): Result<List<NetworkFile>> = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            runCatching {
                val client = readyControlClient()
                val dir = path.ifBlank { rootPath }
                val files = client.listFiles(dir)
                check(FTPReply.isPositiveCompletion(client.replyCode)) {
                    client.replyString.trim().ifEmpty { "Failed to list FTP directory: $dir" }
                }
                files.mapNotNull { file ->
                    if (file.name == "." || file.name == "..") return@mapNotNull null
                    NetworkFile(
                        name = file.name,
                        path = "${dir.trimEnd('/')}/${file.name}",
                        isDirectory = file.isDirectory,
                        size = file.size,
                        modified = file.timestamp?.timeInMillis,
                    )
                }
            }
        }
    }

    override suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        controlMutex.withLock {
            runCatching { readyControlClient().readFileSize(path) }.getOrDefault(-1L)
        }
    }

    // isConnected only checks the local socket; an idle server may already have closed its end.
    private fun readyControlClient(): FTPClient {
        val existing = ftpClient ?: throw IOException("FTP is not connected")
        if (runCatching { existing.isConnected && existing.sendNoOp() }.getOrDefault(false)) return existing
        runCatching { existing.disconnect() }
        ftpClient = null
        val fresh = newClient()
        try {
            if (!fresh.loginAndPrepare()) throw IOException("FTP login failed")
            ftpClient = fresh
            return fresh
        } catch (error: Throwable) {
            runCatching { fresh.disconnect() }
            throw error
        }
    }

    private fun FTPClient.readFileSize(path: String): Long {
        // `SIZE` (RFC 3659) works in binary mode. Some servers only LIST directories.
        return if (sendCommand("SIZE", path) == FTPReply.FILE_STATUS) {
            replyString.trim().substringAfterLast(' ').toLongOrNull()?.takeIf { it >= 0 } ?: -1L
        } else {
            val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
            val name = path.substringAfterLast('/')
            listFiles(parent).firstOrNull { it.name == name && !it.isDirectory }?.size ?: -1L
        }
    }

    override suspend fun openStream(path: String, offset: Long): InputStream {
        require(offset >= 0)
        var opened: InputStream? = null
        try {
            return withContext(Dispatchers.IO) {
                FtpPlaybackStream(offset) { position -> openTransfer(path, position) }.also { opened = it }
            }
        } catch (error: Throwable) {
            // withContext can discard its result if playback was cancelled during the connection.
            opened?.close()
            throw error
        }
    }

    private fun openTransfer(path: String, offset: Long): FtpTransfer {
        // A fresh control connection per stream avoids conflicting with browsing / other streams.
        val streamClient = newClient()
        try {
            if (!streamClient.loginAndPrepare()) throw IOException("FTP login failed")
            val fileSize = streamClient.readFileSize(path)
            if (fileSize >= 0 && offset > fileSize) throw EOFException("FTP restart position is beyond the file")
            if (offset > 0) streamClient.restartOffset = offset
            val raw = streamClient.retrieveFileStream(path)
                ?: throw IOException("Failed to open FTP stream (reply ${streamClient.replyCode})")
            return object : FtpTransfer {
                override val input = raw
                override val size = fileSize
                override fun completed(): Boolean = streamClient.completePendingCommand()
                override fun close() {
                    // This session is never reused. Waiting for 226/QUIT on an expired control
                    // socket blocks seeking/recovery and can skip disconnect when that wait fails.
                    runCatching { raw.close() }
                    runCatching { streamClient.disconnect() }
                }
            }
        } catch (error: Throwable) {
            runCatching { streamClient.disconnect() }
            throw error
        }
    }
}
