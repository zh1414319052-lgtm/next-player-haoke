@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer.feature.player.capture

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoSize
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.frame.FrameExtractor
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkConnectionResolver
import dev.anilbeesetti.nextplayer.core.media.network.datasource.NetworkSessions
import dev.anilbeesetti.nextplayer.core.media.network.datasource.NextDataSourceFactory
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.annotation.Factory

/** Snapshot the selected item and position at the click, even if playback advances afterwards. */
data class FrameCaptureRequest(
    val mediaItem: MediaItem,
    val positionMs: Long,
    val videoSize: VideoSize,
    val displayName: String = FrameImageStore.newDisplayName(),
)

@Factory
class VideoFrameCapture(
    private val context: Context,
    private val resolver: NetworkConnectionResolver,
    private val clientFactory: NetworkClientFactory,
) {
    suspend fun save(request: FrameCaptureRequest, destination: Uri? = null): Uri = withTimeout(20_000) {
        // Dedicated sessions: extraction seeks and cancellation must never close playback's stream.
        val dataSourceFactory = NextDataSourceFactory(context, NetworkSessions(resolver, clientFactory))
        var extractor: FrameExtractor? = null
        try {
            val cleanItem = request.mediaItem.buildUpon().setSubtitleConfigurations(emptyList()).build()
            val builder = FrameExtractor.Builder(context, cleanItem)
                .setSeekParameters(SeekParameters.EXACT)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // Keep source pixels up to 4K; bound unusually large frame allocations without upscaling.
            val size = request.videoSize
            if (maxOf(size.width, size.height) > 3840) {
                val height = (size.height * 3840.0 / maxOf(size.width, size.height)).roundToInt().coerceAtLeast(1)
                builder.setEffects(listOf(Presentation.createForHeight(height)))
            }
            extractor = builder.build()
            // Default HDR extraction tone-maps to BT.709. Playback's HDR surface is untouched.
            val bitmap = extractor.getFrame(request.positionMs.coerceAtLeast(0)).await().bitmap
            try {
                FrameImageStore(context).save(bitmap, request.displayName, destination)
            } finally {
                bitmap.recycle()
            }
        } finally {
            try {
                extractor?.close()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { dataSourceFactory.release() }
            }
        }
    }
}
