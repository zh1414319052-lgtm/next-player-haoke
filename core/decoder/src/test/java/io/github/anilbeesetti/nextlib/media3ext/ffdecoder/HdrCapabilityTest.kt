package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HdrCapabilityTest {
    @Test fun pqAndHlgCannotAdvertiseAnUnmanagedRgbaOutput() {
        val renderer = FfmpegVideoRenderer(0, null, null, 0)
        for (mime in listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_VP9)) {
            for (transfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG)) {
                val format = Format.Builder().setSampleMimeType(mime).setColorInfo(
                    ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
                        .setColorTransfer(transfer).setColorRange(C.COLOR_RANGE_LIMITED).build(),
                ).build()
                assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)))
            }
        }
    }

    @Test fun dolbyVisionWithoutTransferMetadataCannotUseTheSdrOutput() {
        val renderer = FfmpegVideoRenderer(0, null, null, 0)
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)))
    }
}
