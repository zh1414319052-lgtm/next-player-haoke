package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalReadAheadDataSourceTest {
    private val uri = Uri.parse("file:///test.mkv")

    @Test
    fun oneByteReadsAreCoalescedAndEofIsPreserved() {
        val bytes = ByteArray(1_024) { it.toByte() }
        val upstream = MemorySource(bytes)
        val source = LocalReadAheadDataSource(upstream, 128)
        source.open(DataSpec(uri))
        val result = ByteArray(bytes.size)
        for (index in result.indices) assertEquals(1, source.read(result, index, 1))
        assertArrayEquals(bytes, result)
        assertEquals(8, upstream.reads)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
    }

    @Test
    fun reopenAtANewPositionDiscardsBufferedBytes() {
        val source = LocalReadAheadDataSource(MemorySource(ByteArray(128) { it.toByte() }), 64)
        source.open(DataSpec(uri))
        source.read(ByteArray(1), 0, 1)
        source.close()
        source.open(DataSpec.Builder().setUri(uri).setPosition(80).build())
        val result = ByteArray(2)
        assertEquals(2, source.read(result, 0, 2))
        assertArrayEquals(byteArrayOf(80, 81), result)
    }

    @Test
    fun boundedSpecAndZeroReadsDoNotOverRead() {
        val upstream = MemorySource(ByteArray(128) { it.toByte() })
        val source = LocalReadAheadDataSource(upstream, 64)
        assertEquals(3L, source.open(DataSpec.Builder().setUri(uri).setPosition(20).setLength(3).build()))
        assertEquals(0, source.read(ByteArray(1), 0, 0))
        assertEquals(0, upstream.reads)
        val result = ByteArray(10)
        assertEquals(3, source.read(result, 0, 10))
        assertArrayEquals(byteArrayOf(20, 21, 22), result.copyOf(3))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(result, 0, 10))
    }

    @Test
    fun largeReadsPassThroughWithoutExtraCopy() {
        val upstream = MemorySource(ByteArray(128) { it.toByte() })
        val source = LocalReadAheadDataSource(upstream, 16)
        source.open(DataSpec(uri))
        val result = ByteArray(64)
        assertEquals(64, source.read(result, 0, 64))
        assertEquals(1, upstream.reads)
        assertArrayEquals(ByteArray(64) { it.toByte() }, result)
    }

    private class MemorySource(private val bytes: ByteArray) : DataSource {
        var reads = 0
        private var position = 0
        private var end = bytes.size
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) bytes.size else minOf(bytes.size, position + dataSpec.length.toInt())
            return (end - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (position == end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun close() { uri = null }
        override fun getUri(): Uri? = uri
        override fun addTransferListener(transferListener: TransferListener) = Unit
    }
}
