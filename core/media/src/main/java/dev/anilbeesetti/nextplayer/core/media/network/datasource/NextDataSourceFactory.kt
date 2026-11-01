package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import dev.anilbeesetti.nextplayer.core.common.Logger
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import org.koin.core.annotation.Single

/**
 * The player's data source factory: Media3's own [DefaultDataSource] for local and http(s) media,
 * and [NetworkDataSource] for the `smb`/`ftp`/`sftp`/`webdav` schemes it doesn't handle.
 */
@UnstableApi
@Single
class NextDataSourceFactory(
    private val context: Context,
    private val sessions: NetworkSessions,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = SchemeDispatchingDataSource(
        default = DefaultDataSource.Factory(context).createDataSource(),
        network = NetworkDataSource(sessions),
    )

    /** Disconnects the network client held for playback. Call when the player is released. */
    suspend fun release() = sessions.release()
}

/**
 * Picks the delegate on the first [open], since the scheme is only known then. Media3 asks the
 * factory for a source before it knows which item it will play.
 */
@UnstableApi
private class SchemeDispatchingDataSource(
    private val default: DataSource,
    private val network: DataSource,
) : DataSource {

    private var delegate: DataSource? = null
    private val localReadAhead = LocalReadAheadDataSource(default)
    private var openedAt = 0L
    private var bytesRead = 0L
    private var nextReport = 256 * 1024L

    override fun open(dataSpec: DataSpec): Long {
        val target = when {
            NetworkUri.isNetworkUri(dataSpec.uri) -> network
            dataSpec.uri.scheme in listOf(null, "file", "content", "android.resource", "asset") -> localReadAhead
            else -> default
        }
        delegate = target
        bytesRead = 0
        nextReport = 256 * 1024L
        openedAt = SystemClock.elapsedRealtime()
        val length = target.open(dataSpec)
        Logger.logInfo("PlaybackIO", "Open scheme=${dataSpec.uri.scheme} position=${dataSpec.position} length=$length tookMs=${SystemClock.elapsedRealtime() - openedAt}")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = checkNotNull(delegate) { "read() before open()" }.read(buffer, offset, length)
        if (count > 0) {
            bytesRead += count
            if (bytesRead >= nextReport) {
                Logger.logInfo("PlaybackIO", "Read bytes=$bytesRead elapsedMs=${SystemClock.elapsedRealtime() - openedAt}")
                nextReport = if (nextReport < 16 * 1024 * 1024) 16 * 1024 * 1024 else nextReport * 2
            }
        }
        return count
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        network.addTransferListener(transferListener)
    }

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()
}
