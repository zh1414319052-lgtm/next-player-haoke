@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize

/** Media3's effects sink currently omits video-size callbacks (b/292111083). */
internal fun videoSurfaceSize(reported: VideoSize, tracks: Tracks): VideoSize {
    if (reported.width > 0 && reported.height > 0) return reported
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (index in 0 until group.length) {
            if (!group.isTrackSelected(index)) continue
            val format = group.getTrackFormat(index)
            if (format.width <= 0 || format.height <= 0) continue
            return format.displayVideoSize()
        }
    }
    return VideoSize.UNKNOWN
}

internal fun Format.displayVideoSize(): VideoSize {
    if (width <= 0 || height <= 0) return VideoSize.UNKNOWN
    val ratio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    return if (rotationDegrees == 90 || rotationDegrees == 270) {
        VideoSize(height, width, 1f / ratio)
    } else {
        VideoSize(width, height, ratio)
    }
}

@Composable
internal fun rememberVideoSurfaceSize(player: Player): VideoSize {
    fun readSize() = videoSurfaceSize(
        player.videoSize,
        if (player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) player.currentTracks else Tracks.EMPTY,
    )
    var size by remember(player) { mutableStateOf(readSize()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                size = readSize()
            }
        }
        player.addListener(listener)
        size = readSize()
        onDispose { player.removeListener(listener) }
    }
    return size
}
