@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VideoSurfaceSizeTest {
    private fun format() = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(720).setHeight(480)
    private fun group(format: Format, selected: Boolean) = Tracks.Group(
        TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected),
    )

    @Test fun rendererReportedSizeTakesPrecedenceOverEncodedTrackDimensions() {
        val reported = VideoSize(1280, 720)
        assertEquals(reported, videoSurfaceSize(reported, Tracks(listOf(group(format().build(), true)))))
    }

    @Test fun effectsFallbackUsesOnlySelectedVideoTrack() {
        val selected = format().setWidth(1920).setHeight(1080).build()
        val tracks = Tracks(listOf(group(format().build(), false), group(selected, true)))
        assertEquals(VideoSize(1920, 1080), videoSurfaceSize(VideoSize.UNKNOWN, tracks))
    }

    @Test fun rotatedAnamorphicVideoPreservesDisplayAspectRatio() {
        val source = format().setPixelWidthHeightRatio(2f)
        assertEquals(VideoSize(720, 480, 2f), source.setRotationDegrees(0).build().displayVideoSize())
        assertEquals(VideoSize(480, 720, 0.5f), source.setRotationDegrees(90).build().displayVideoSize())
        assertEquals(VideoSize(480, 720, 0.5f), source.setRotationDegrees(270).build().displayVideoSize())
    }

    @Test fun missingOrDeselectedVideoDoesNotKeepThePreviousItemSize() {
        assertEquals(VideoSize.UNKNOWN, videoSurfaceSize(VideoSize.UNKNOWN, Tracks.EMPTY))
        assertEquals(VideoSize.UNKNOWN, videoSurfaceSize(VideoSize.UNKNOWN, Tracks(listOf(group(format().build(), false)))))
        assertEquals(VideoSize.UNKNOWN, format().setWidth(Format.NO_VALUE).build().displayVideoSize())
    }
}
