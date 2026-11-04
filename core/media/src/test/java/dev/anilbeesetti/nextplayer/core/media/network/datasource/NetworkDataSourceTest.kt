package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkConnectionResolver
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkDataSourceTest {
    @Test
    fun prematureNetworkEofIsAnErrorAndNotACompletedVideo() {
        val source = source(size = 5)
        try {
            assertEquals(5L, source.open(DataSpec(Uri.parse("ftp://localhost/movie"))))
            assertEquals(3, source.read(ByteArray(8), 0, 8))
            assertThrows(EOFException::class.java) { source.read(ByteArray(8), 0, 8) }
        } finally {
            source.close()
        }
    }

    @Test
    fun normalEofAndZeroLengthReadAreUnchanged() {
        val source = source(size = 3)
        try {
            source.open(DataSpec(Uri.parse("sftp://localhost/movie")))
            assertEquals(0, source.read(ByteArray(8), 0, 0))
            assertEquals(3, source.read(ByteArray(8), 0, 8))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        } finally {
            source.close()
        }
    }

    @Test
    fun boundedRangeAndUnknownSizePreserveTheirEof() {
        val source = source(size = -1)
        try {
            source.open(DataSpec.Builder().setUri("ftp://localhost/movie").setLength(2).build())
            assertEquals(2, source.read(ByteArray(8), 0, 8))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
            source.close()
            assertEquals(-1L, source.open(DataSpec(Uri.parse("ftp://localhost/movie"))))
            assertEquals(3, source.read(ByteArray(8), 0, 8))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        } finally {
            source.close()
        }
    }

    private fun source(size: Long): NetworkDataSource {
        val client = object : NetworkClient {
            override val rootPath = "/"
            override suspend fun connect() = Result.success(Unit)
            override suspend fun disconnect() = Unit
            override fun isConnected() = true
            override suspend fun listFiles(path: String) = Result.success(emptyList<NetworkFile>())
            override suspend fun fileSize(path: String) = size
            override suspend fun openStream(path: String, offset: Long) = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        return NetworkDataSource(NetworkSessions(NetworkConnectionResolver { null }, NetworkClientFactory { client }))
    }
}
