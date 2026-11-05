package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ColorModeVideoRendererTest {
    @Test fun hdrAndSdrTracksUseSeparateRenderers() {
        val hdr = ColorModeVideoRenderer(RecordingRenderer(), hdr = true)
        val sdr = ColorModeVideoRenderer(RecordingRenderer(), hdr = false)
        val formats = listOf(
            format(C.COLOR_TRANSFER_SDR),
            format(C.COLOR_TRANSFER_ST2084),
            format(C.COLOR_TRANSFER_HLG),
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build(),
        )
        for ((index, format) in formats.withIndex()) {
            assertEquals(if (index == 0) C.FORMAT_UNSUPPORTED_SUBTYPE else C.FORMAT_HANDLED,
                RendererCapabilities.getFormatSupport(hdr.capabilities.supportsFormat(format)))
            assertEquals(if (index == 0) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE,
                RendererCapabilities.getFormatSupport(sdr.capabilities.supportsFormat(format)))
        }
    }

    @Test fun hdrSkipsEffectsButRetainsSurfaceMessagesAndSdrEffects() {
        val native = RecordingRenderer()
        val hdr = ColorModeVideoRenderer(native, hdr = true)
        hdr.handleMessage(Renderer.MSG_SET_VIDEO_EFFECTS, emptyList<Any>())
        hdr.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null)
        assertEquals(listOf(Renderer.MSG_SET_VIDEO_OUTPUT), native.messages)

        val processed = RecordingRenderer()
        ColorModeVideoRenderer(processed, hdr = false).handleMessage(Renderer.MSG_SET_VIDEO_EFFECTS, emptyList<Any>())
        assertEquals(listOf(Renderer.MSG_SET_VIDEO_EFFECTS), processed.messages)
    }

    private fun format(transfer: Int) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
        .setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setLumaBitdepth(10)
            .setChromaBitdepth(10).setColorTransfer(transfer).build()).build()

    private class RecordingRenderer : BaseRenderer(C.TRACK_TYPE_VIDEO) {
        val messages = mutableListOf<Int>()
        override fun getName() = "RecordingRenderer"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_HANDLED)
        override fun isReady() = true
        override fun isEnded() = false
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) = Unit
        override fun handleMessage(messageType: Int, message: Any?) { messages += messageType }
    }
}
