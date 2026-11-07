package dev.anilbeesetti.nextplayer.feature.player.capture

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FrameImageStoreTest {
    private lateinit var provider: ImageProvider
    @Before fun setup() {
        provider = ImageProvider()
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun publishesLosslessPngOnlyAfterWritingAndNeverUsesVideoTitlesAsPaths() = runTest {
        val image = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        try {
            val uri = FrameImageStore(RuntimeEnvironment.getApplication()).save(image, FrameImageStore.newDisplayName())
            assertEquals(provider.uri, uri)
            assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            assertEquals("Pictures/Next Player", provider.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            assertEquals("image/png", provider.inserted!!.getAsString(MediaStore.Images.Media.MIME_TYPE))
            assertEquals(0, provider.updated!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            assertFalse(provider.deleted)
            val decoded = BitmapFactory.decodeFile(provider.file.absolutePath)
            assertEquals(64, decoded.width)
            assertEquals(32, decoded.height)
            assertEquals(android.graphics.Color.BLUE, decoded.getPixel(40, 20))
            decoded.recycle()
        } finally { image.recycle() }
    }

    @Test fun failedWriteRemovesOnlyItsOwnPendingRowAndDoesNotPublishSuccess() = runTest {
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        provider.failOpen = true
        try {
            try {
                FrameImageStore(RuntimeEnvironment.getApplication()).save(image, FrameImageStore.newDisplayName())
                fail("Expected storage failure")
            } catch (_: IOException) { }
            assertTrue(provider.deleted)
            assertNull(provider.updated)
        } finally { image.recycle() }
    }

    @Test fun failedPublicationRemovesTheUnpublishedRow() = runTest {
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        provider.failUpdate = true
        try {
            try {
                FrameImageStore(RuntimeEnvironment.getApplication()).save(image, FrameImageStore.newDisplayName())
                fail("Expected publication failure")
            } catch (_: IOException) { }
            assertTrue(provider.deleted)
        } finally { image.recycle() }
    }

    @Test @Config(sdk = [28]) fun olderPhoneSavesToTheUserChosenDocumentWithoutGalleryPermission() = runTest {
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            FrameImageStore(RuntimeEnvironment.getApplication()).save(image, FrameImageStore.newDisplayName(), provider.uri)
            assertNull(provider.inserted)
            assertNull(provider.updated)
            assertFalse(provider.deleted)
            assertTrue(provider.file.length() > 0)
        } finally { image.recycle() }
    }

    class ImageProvider : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/images/media/77")
        lateinit var file: File
        var inserted: ContentValues? = null
        var updated: ContentValues? = null
        var deleted = false
        var failOpen = false
        var failUpdate = false
        override fun onCreate(): Boolean { file = File.createTempFile("frame-store-test", ".png", context!!.cacheDir); return true }
        override fun insert(uri: Uri, values: ContentValues?): Uri { inserted = ContentValues(values); return this.uri }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals(this.uri, uri)
            if (failOpen) throw IOException("Synthetic storage failure")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(this.uri, uri); updated = ContentValues(values); return if (failUpdate) 0 else 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { assertEquals(this.uri, uri); deleted = true; return 1 }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String = "image/png"
    }
}
