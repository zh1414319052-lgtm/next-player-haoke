package dev.anilbeesetti.nextplayer.feature.player.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import dev.anilbeesetti.nextplayer.core.common.extensions.convertToUTF8
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import java.nio.charset.Charset

internal fun subtitleMimeType(displayName: String?, path: String?, providerType: String?): String {
    return mimeTypeFromSubtitleName(displayName) ?: mimeTypeFromSubtitleName(path) ?: when (providerType?.substringBefore(';')?.lowercase()) {
        MimeTypes.TEXT_SSA, "text/x-ass", "application/x-ass", "application/x-ssa" -> MimeTypes.TEXT_SSA
        MimeTypes.TEXT_VTT -> MimeTypes.TEXT_VTT
        MimeTypes.APPLICATION_TTML -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

private fun mimeTypeFromSubtitleName(name: String?): String? = when (name?.substringAfterLast('.', "")?.lowercase()) {
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt" -> MimeTypes.TEXT_VTT
    "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    else -> null
}

val Uri.isSchemaContent: Boolean
    get() = ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)

suspend fun Context.uriToSubtitleConfiguration(
    uri: Uri,
    subtitleEncoding: String = "",
    isSelected: Boolean = false,
): MediaItem.SubtitleConfiguration {
    val charset = if (subtitleEncoding.isNotEmpty() && Charset.isSupported(subtitleEncoding)) {
        Charset.forName(subtitleEncoding)
    } else {
        null
    }
    val label = if (uri.isSchemaContent) getFilenameFromUri(uri) else uri.lastPathSegment.orEmpty()
    // SAF document IDs may be opaque and Android can label .ass as audio/aac.
    val providerType = if (uri.isSchemaContent) runCatching { contentResolver.getType(uri) }.getOrNull() else null
    val mimeType = subtitleMimeType(label, uri.path, providerType)
    val utf8ConvertedUri = convertToUTF8(uri = uri, charset = charset)
    return MediaItem.SubtitleConfiguration.Builder(utf8ConvertedUri).apply {
        setId(uri.toString())
        setMimeType(mimeType)
        setLabel(label)
        if (isSelected) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    }.build()
}

@Suppress("DEPRECATION")
fun Bundle.getParcelableUriArray(key: String): ArrayList<out Parcelable>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, Uri::class.java)
    } else {
        getParcelableArrayList(key)
    }
}
