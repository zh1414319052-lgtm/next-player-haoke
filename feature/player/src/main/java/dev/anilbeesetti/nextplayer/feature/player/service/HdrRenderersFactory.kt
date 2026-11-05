package dev.anilbeesetti.nextplayer.feature.player.service

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/** Keeps HDR on a native Surface and isolates the SDR effects graph across playlist changes. */
@UnstableApi
internal class HdrRenderersFactory(context: Context) : NextRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val sdrRenderers = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, sdrRenderers,
        )
        // Preserve FFmpeg's identity for DecoderManager's runtime switching contract.
        out.addAll(sdrRenderers.map { if (it is FfmpegVideoRenderer) it else ColorModeVideoRenderer(it, hdr = false) })

        val hdrRenderers = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, hdrRenderers,
        )
        out.addAll(hdrRenderers.map { ColorModeVideoRenderer(it, hdr = true) })
    }
}

internal fun Format.isHdrVideo(): Boolean =
    ColorInfo.isTransferHdr(colorInfo) || sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION

@UnstableApi
internal class ColorModeVideoRenderer(delegate: Renderer, private val hdr: Boolean) : ForwardingRenderer(delegate) {
    private val colorCapabilities = object : RendererCapabilities by delegate.capabilities {
        override fun supportsFormat(format: Format): Int =
            if (format.isHdrVideo() == hdr) delegate.capabilities.supportsFormat(format)
            else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
    }

    override fun getCapabilities(): RendererCapabilities = colorCapabilities

    override fun handleMessage(messageType: Int, message: Any?) {
        // Even empty effects create a persistent Media3 graph whose output color space
        // is fixed by its first stream. HDR must never enter the SDR interpolation graph.
        if (hdr && messageType == Renderer.MSG_SET_VIDEO_EFFECTS) return
        super.handleMessage(messageType, message)
    }
}
