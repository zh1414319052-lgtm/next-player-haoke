package dev.anilbeesetti.nextplayer.feature.player.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Publish only a completely written PNG; never overwrite a gallery item or derive paths from titles. */
internal class FrameImageStore(private val context: Context) {
    suspend fun save(bitmap: Bitmap, displayName: String, destination: Uri? = null): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ownsRow = destination == null
        require(!ownsRow || Build.VERSION.SDK_INT >= 29) { "Older devices require a document destination" }
        val uri = destination ?: resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Next Player")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        ) ?: throw IOException("Could not create image")
        var committed = false
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("PNG encoding failed")
            } ?: throw IOException("Could not open image")
            ensureActive()
            if (ownsRow && resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) != 1) {
                throw IOException("Could not publish image")
            }
            committed = true
            uri
        } finally {
            if (ownsRow && !committed) resolver.delete(uri, null, null)
        }
    }

    companion object {
        fun newDisplayName(): String = "NextPlayer_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date()) +
            "_" + UUID.randomUUID().toString().take(8) + ".png"
    }
}
