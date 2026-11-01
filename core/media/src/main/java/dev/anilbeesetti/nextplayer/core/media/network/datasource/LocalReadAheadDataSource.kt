package dev.anilbeesetti.nextplayer.core.media.network.datasource

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Amortizes small extractor reads without retaining data across an open/seek. */
@UnstableApi
internal class LocalReadAheadDataSource(
    private val upstream: DataSource,
    bufferSize: Int = 64 * 1024,
) : DataSource by upstream {
    private val buffer = ByteArray(bufferSize.also { require(it > 0) })
    private var position = 0
    private var limit = 0

    override fun open(dataSpec: DataSpec): Long {
        position = 0
        limit = 0
        return upstream.open(dataSpec)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= target.size - length)
        if (length == 0) return 0
        if (position == limit) {
            if (length >= buffer.size) return upstream.read(target, offset, length)
            val count = upstream.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT || count == 0) return count
            position = 0
            limit = count
        }
        val count = minOf(length, limit - position)
        buffer.copyInto(target, offset, position, position + count)
        position += count
        return count
    }

    override fun close() {
        position = 0
        limit = 0
        upstream.close()
    }
}
